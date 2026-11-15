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
		l.add(new Def("Звёздная ночь", true, 1, c(0x050816), c(0x0B1030), c(0x1A2050)));
		l.add(new Def("Космос", true, 1, c(0x000000), c(0x07031A), c(0x150A30)));
		l.add(new Def("Галактика (анимация)", true, 12, c(0x0A0420, 0x1A0A4A, 0x2B0A3D, 0x08133A), c(0x10062E, 0x241060, 0x35104F, 0x0E1C52), c(0x4B2A9A, 0x8A3FB0, 0x2E5BD0)));
		l.add(new Def("Северное сияние (анимация)", true, 7, c(0x04101C, 0x062A22, 0x1B0B3A, 0x05222C), c(0x082A30, 0x0C3D2E, 0x2A1458), c(0x2AFFB0, 0x8A5BFF, 0x3DE0FF)));
		l.add(new Def("Закат (анимация)", false, 9, c(0xFF7A3D, 0xFF3D6E, 0x7A2DD1, 0xFF7A3D), c(0xFFB36B, 0xFF6E8E, 0xA24BE0), c(0xFFD2A0, 0xFFA0C0, 0xD0A0FF)));
		l.add(new Def("Неон (анимация)", true, 5, c(0xFF00AA, 0x00E5FF, 0x7A00FF), c(0x990066, 0x007F99, 0x4A0099), c(0xFF66CC, 0x66F0FF, 0xB366FF)));
		l.add(new Def("Радуга (анимация)", false, 4, c(0xFF4B4B, 0xFFE14D, 0x5CFF7C, 0x4DE1FF, 0x4D7CFF, 0xB06BFF), c(0xFF8A8A, 0xFFEE88, 0x9BFFB0, 0x9BEFFF, 0x9BB5FF, 0xD2A6FF), c(0xFFFFFF)));
		l.add(new Def("Рассвет", false, 1, c(0xFFCF9E), c(0xFFE3C4), c(0xFFB6C9)));
		l.add(new Def("Кровавая луна", true, 1, c(0x2A0000), c(0x4D0505), c(0x330000)));
		l.add(new Def("Лёд", false, 1, c(0xAEE6FF), c(0xD8F6FF), c(0xFFFFFF)));
		l.add(new Def("Сумерки", true, 1, c(0x1B1740), c(0x2D2766), c(0x453C8F)));
		l.add(new Def("Глубокий океан (анимация)", false, 10, c(0x041E3A, 0x063A5E, 0x0A5A7A), c(0x08304F, 0x0A4D75, 0x108DA0), c(0x2FA6C8, 0x6FD0E8)));
		l.add(new Def("Тьма", false, 1, c(0x000000), c(0x000000), c(0x111111)));
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

	/** Названия для переключателя: первый пункт - свои цвета из настроек. */
	public static synchronized String[] names() {
		String[] n = new String[LIST.size() + 1];
		n[0] = "Свои цвета";
		for (int i = 0; i < LIST.size(); i++) n[i + 1] = LIST.get(i).name;
		return n;
	}

	public static synchronized Def current(int idx) {
		return idx >= 1 && idx <= LIST.size() ? LIST.get(idx - 1) : null;
	}
}
