package dev.enchanted.client;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Скины игроков как в Lunar: лаунчер Enchanted кладёт свой скин на сервер скинов, мод подтягивает скины всех игроков
 * по нику и подменяет их при отрисовке. Свой скин берётся из локального файла (работает и без сервера).
 * Настройки пишет лаунчер в config/enchanted-skins.json: {"server":"http://...","nick":"Ник","file":"путь.png","slim":false}.
 * Всё сетевое - в фоновых потоках, в рендере только чтение из кэша (без аллокаций, пока скин не менялся).
 */
public final class Skins {
	private static final long TTL_OK = 300_000L, TTL_NONE = 60_000L;

	private static final class Entry {
		volatile Identifier tex;
		volatile boolean slim, found;
		volatile long t;
		volatile String etag;
		PlayerSkin lastOrig, lastRes; // только рендер-поток
	}

	private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
	private static final Set<String> BUSY = ConcurrentHashMap.newKeySet();
	private static ExecutorService pool;
	private static HttpClient http;
	private static String server = "", me = "";
	private static Path file;
	private static boolean localSlim, broken;
	private static int seq;
	private static Method nameM;

	/** Включён ли модуль (есть сервер или локальный скин). */
	public static volatile boolean active;

	private Skins() {
	}

	public static void init() {
		try {
			Path f = FabricLoader.getInstance().getConfigDir().resolve("enchanted-skins.json");
			if (!Files.exists(f)) return;
			JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
			String s = o.has("server") ? o.get("server").getAsString().trim() : "";
			while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
			server = s.startsWith("http://") || s.startsWith("https://") ? s : "";
			me = o.has("nick") ? o.get("nick").getAsString().trim() : "";
			localSlim = o.has("slim") && o.get("slim").getAsBoolean();
			if (o.has("file") && !o.get("file").getAsString().isBlank()) file = Path.of(o.get("file").getAsString());
			if (server.isEmpty() && file == null) return;
			http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
			pool = Executors.newFixedThreadPool(2, r -> {
				Thread th = new Thread(r, "enchanted-skins");
				th.setDaemon(true);
				return th;
			});
			active = true;
		} catch (Throwable t) {
			active = false;
		}
	}

	/** Ник из GameProfile (authlib не переименовывается, поэтому достаём рефлексией: работает и со старым getName, и с record name). */
	public static String nameOf(Object profile) {
		if (profile == null) return null;
		try {
			if (nameM == null) {
				for (String n : new String[] {"name", "getName"}) {
					try {
						nameM = profile.getClass().getMethod(n);
						break;
					} catch (NoSuchMethodException ignored) {
					}
				}
				if (nameM == null) return null;
			}
			return (String) nameM.invoke(profile);
		} catch (Throwable t) {
			return null;
		}
	}

	/** Вызывается из PlayerInfo.getSkin() на каждый кадр для каждого игрока: быстрый путь - одно чтение из map. */
	public static PlayerSkin override(String name, PlayerSkin orig) {
		if (!active || broken || name == null || orig == null) return orig;
		String key = name.toLowerCase(Locale.ROOT);
		Entry e = CACHE.computeIfAbsent(key, k -> new Entry());
		long now = System.currentTimeMillis();
		if (now - e.t > (e.found ? TTL_OK : TTL_NONE) && BUSY.add(key)) {
			final Entry fe = e;
			pool.execute(() -> {
				try {
					fetch(key, name, fe);
				} catch (Throwable t) {
					fe.t = System.currentTimeMillis() - TTL_NONE + 20_000L; // ошибка сети: повтор через ~20 с
				} finally {
					BUSY.remove(key);
				}
			});
		}
		Identifier tex = e.tex;
		if (!e.found || tex == null) return orig;
		if (e.lastOrig == orig) return e.lastRes;
		try {
			PlayerSkin r = build(orig, tex, e.slim);
			e.lastOrig = orig;
			e.lastRes = r;
			return r;
		} catch (Throwable t) {
			broken = true; // структура PlayerSkin другая - молча отключаемся
			return orig;
		}
	}

	// ---------------- загрузка (фоновый поток) ----------------

	private static void fetch(String key, String name, Entry e) throws Exception {
		long now = System.currentTimeMillis();
		byte[] data = null;
		boolean slim = false;
		String etag = null;
		if (name.equalsIgnoreCase(me) && file != null && Files.isRegularFile(file)) {
			data = Files.readAllBytes(file);
			slim = localSlim;
			etag = "local-" + Files.getLastModifiedTime(file).toMillis();
			if (etag.equals(e.etag)) {
				e.t = now;
				return;
			}
		} else if (!server.isEmpty()) {
			HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(server + "/skin/" + URLEncoder.encode(name, StandardCharsets.UTF_8) + ".png"))
					.timeout(Duration.ofSeconds(6)).GET();
			if (e.etag != null && e.found) rb.header("If-None-Match", e.etag);
			HttpResponse<byte[]> r = http.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
			int sc = r.statusCode();
			if (sc == 304) {
				e.t = now;
				return;
			}
			if (sc == 200 && r.body().length > 0 && r.body().length <= 65536) {
				data = r.body();
				slim = "slim".equalsIgnoreCase(r.headers().firstValue("X-Model").orElse(""));
				etag = r.headers().firstValue("ETag").orElse(null);
			} else {
				e.found = false;
				e.t = now;
				return;
			}
		} else {
			e.t = now;
			return;
		}
		final byte[] d = data;
		final boolean fs = slim;
		final String fe = etag;
		Minecraft.getInstance().execute(() -> upload(e, d, fs, fe));
	}

	/** Рендер-поток: картинка -> текстура. Новый id на каждую загрузку, чтобы обновлённый скин подхватился сразу. */
	private static void upload(Entry e, byte[] data, boolean slim, String etag) {
		try {
			NativeImage img = NativeImage.read(new ByteArrayInputStream(data));
			if (img.getWidth() != 64 || img.getHeight() != 64) {
				img.close();
				e.found = false;
				e.t = System.currentTimeMillis();
				return;
			}
			DynamicTexture tex = makeTexture(img);
			Identifier id = Identifier.fromNamespaceAndPath("enchanted_client", "skins/s" + (++seq));
			Minecraft.getInstance().getTextureManager().register(id, tex);
			e.slim = slim;
			e.etag = etag;
			e.lastOrig = null;
			e.tex = id;
			e.found = true;
			e.t = System.currentTimeMillis();
		} catch (Throwable t) {
			e.found = false;
			e.t = System.currentTimeMillis();
		}
	}

	/** Конструктор DynamicTexture отличается между версиями игры - подбираем подходящий. */
	private static DynamicTexture makeTexture(NativeImage img) throws Exception {
		for (Constructor<?> c : DynamicTexture.class.getConstructors()) {
			Class<?>[] pt = c.getParameterTypes();
			DynamicTexture t = null;
			if (pt.length == 1 && pt[0] == NativeImage.class) t = (DynamicTexture) c.newInstance(img);
			else if (pt.length == 2 && pt[0] == Supplier.class && pt[1] == NativeImage.class) {
				Supplier<String> label = () -> "enchanted skin";
				t = (DynamicTexture) c.newInstance(label, img);
			}
			if (t != null) {
				try {
					t.getClass().getMethod("upload").invoke(t);
				} catch (Throwable ignored) {
				}
				return t;
			}
		}
		throw new IllegalStateException("DynamicTexture");
	}

	// ---------------- подмена PlayerSkin ----------------

	/** Копия PlayerSkin с другой текстурой тела и моделью; плащ и элитры остаются. Структуру читаем через record-компоненты. */
	private static PlayerSkin build(PlayerSkin o, Identifier tex, boolean slim) throws Exception {
		RecordComponent[] rc = PlayerSkin.class.getRecordComponents();
		Object[] v = new Object[rc.length];
		Class<?>[] types = new Class<?>[rc.length];
		for (int i = 0; i < rc.length; i++) {
			types[i] = rc[i].getType();
			Method m = rc[i].getAccessor();
			m.setAccessible(true);
			v[i] = m.invoke(o);
		}
		boolean bodyDone = false;
		for (int i = 0; i < rc.length; i++) {
			if (types[i].isEnum()) { // тип модели: SLIM / WIDE
				for (Object c : types[i].getEnumConstants()) {
					if (slim == ((Enum<?>) c).name().toUpperCase(Locale.ROOT).contains("SLIM")) {
						v[i] = c;
						break;
					}
				}
			} else if (!bodyDone && v[i] != null && !(v[i] instanceof Boolean)) { // первый ассет - тело
				v[i] = asset(v[i], tex);
				bodyDone = true;
			}
		}
		Constructor<PlayerSkin> k = PlayerSkin.class.getDeclaredConstructor(types);
		k.setAccessible(true);
		return k.newInstance(v);
	}

	private static Object asset(Object old, Identifier tex) throws Exception {
		if (old instanceof Identifier) return tex;
		for (Constructor<?> k : old.getClass().getDeclaredConstructors()) {
			Class<?>[] pt = k.getParameterTypes();
			Object[] a = new Object[pt.length];
			boolean ok = pt.length > 0;
			for (int j = 0; j < pt.length && ok; j++) {
				if (pt[j] == Identifier.class) a[j] = tex;
				else if (pt[j] == String.class) a[j] = "";
				else ok = false;
			}
			if (ok) {
				k.setAccessible(true);
				return k.newInstance(a);
			}
		}
		throw new IllegalStateException("asset");
	}
}
