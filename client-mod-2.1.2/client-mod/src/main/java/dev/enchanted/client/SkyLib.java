package dev.enchanted.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

/** Небеса: встроенные пресеты (часть с анимацией) и свои из папки skies/ (*.json). */
public final class SkyLib {
	/** Одно небо: списки цветов плавно перетекают друг в друга (если цветов больше одного - это анимация). */
	public static final class Def {
		final String name;
		final int[] sky, fog, cloud;
		public final boolean stars;
		/** Имя картинок неба (textures/gui/sky/<pano>_0..5.png) или null. */
		public String pano;
		final double speed; // секунд на переход к следующему цвету

		Def(String name, boolean stars, double speed, int[] sky, int[] fog, int[] cloud) {
			this.name = name; this.stars = stars; this.speed = Math.max(0.5, speed);
			this.sky = sky; this.fog = fog.length == 0 ? sky : fog; this.cloud = cloud.length == 0 ? sky : cloud;
		}

		public int sky() { return sample(sky); }
		public int fog() { return sample(fog); }
		public int cloud() { return sample(cloud); }

		private int sample(int[] c) {
			if (c.length == 1) return c[0];
			double t = (System.currentTimeMillis() / 1000.0) / speed;
			double pos = (t - Math.floor(t / c.length) * c.length);
			int i = (int) Math.floor(pos) % c.length;
			double f = pos - Math.floor(pos);
			f = f * f * (3 - 2 * f);
			int a = c[i], b = c[(i + 1) % c.length];
			int r = (int) (((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
			int g = (int) (((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
			int bl = (int) ((a & 255) * (1 - f) + (b & 255) * f);
			return (r << 16) | (g << 8) | bl;
		}
	}

	private static final List<Def> LIST = new ArrayList<>();

	private static int[] c(int... v) { return v; }

	private static List<Def> builtins() {
		List<Def> l = new ArrayList<>();
		l.add(new Def("Тьма", false, 1, c(0x000000), c(0x000000), c(0x000000)));
		String[][] img = {
			{"Акаме", "akame", "1D171A"}, {"Фиолет", "violet", "140C1F"}, {"Облака", "clouds", "B9C5CD"},
			{"Туман", "mist", "A5B5B1"}, {"Зелёное", "green", "054F00"}, {"Синяя ночь", "blue_night", "385063"},
			{"Бирюза", "teal", "023228"}, {"Серая ночь", "gray_night", "575B5E"}};
		for (String[] a : img) {
			int col = Integer.parseInt(a[2], 16);
			Def d = new Def(a[0], false, 1, c(col), c(col), c(col));
			d.pano = a[1];
			l.add(d);
		}
		return l;
	}

	static {
		scan();
	}

	/** Путь к папке со своими небесами (рядом с модами, внутри папки игры). */
	public static Path dir() {
		return FabricLoader.getInstance().getGameDir().resolve("skies");
	}

	public static synchronized void scan() {
		LIST.clear();
		LIST.addAll(builtins());
		try {
			Path d = dir();
			if (!Files.isDirectory(d)) return;
			List<Path> files;
			try (Stream<Path> s = Files.list(d)) {
				files = s.filter(x -> x.getFileName().toString().toLowerCase().endsWith(".json")).sorted().toList();
			}
			for (Path p : files) {
				try {
					Def def = parse(p);
					if (def != null) LIST.add(def);
				} catch (Exception ignored) {
				}
			}
		} catch (Exception ignored) {
		}
	}

	private static Def parse(Path p) throws Exception {
		JsonObject o = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
		String fn = p.getFileName().toString();
		String name = o.has("name") ? o.get("name").getAsString() : fn.substring(0, fn.length() - 5);
		int[] sky = colors(o.get("sky"));
		if (sky.length == 0) return null;
		boolean stars = o.has("stars") && o.get("stars").getAsBoolean();
		double speed = o.has("speed") ? o.get("speed").getAsDouble() : 8;
		return new Def("* " + name, stars, speed, sky, colors(o.get("fog")), colors(o.get("cloud")));
	}

	private static int[] colors(JsonElement e) {
		if (e == null || e.isJsonNull()) return new int[0];
		List<Integer> r = new ArrayList<>();
		if (e.isJsonArray()) {
			JsonArray a = e.getAsJsonArray();
			for (JsonElement x : a) addColor(r, x);
		} else {
			addColor(r, e);
		}
		int[] out = new int[r.size()];
		for (int i = 0; i < out.length; i++) out[i] = r.get(i);
		return out;
	}

	private static void addColor(List<Integer> r, JsonElement x) {
		try {
			String s = x.getAsString().trim();
			if (s.startsWith("#")) s = s.substring(1);
			r.add(Integer.parseInt(s, 16) & 0xFFFFFF);
		} catch (Exception ignored) {
		}
	}

	/** Названия для переключателя. */
	public static synchronized String[] names() {
		String[] n = new String[LIST.size()];
		for (int i = 0; i < n.length; i++) n[i] = LIST.get(i).name;
		return n;
	}

	public static synchronized Def current(int idx) {
		return LIST.isEmpty() ? null : LIST.get(Math.floorMod(idx, LIST.size()));
	}
}
