package dev.enchanted.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/** Настройки клиента: модули, их параметры, клавиши, позиции HUD, сохранение в config/enchanted-client.json. */
public final class Cfg {
	static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("enchanted-client.json");

	// ---- темы интерфейса: основной цвет + второй цвет для градиентов ----
	static final String[] THEMES = {"Белый", "Океан", "Аметист", "Рубин", "Янтарь", "Неон", "Закат", "Мята"};
	static final int[] ACCENT = {0xFFF2F2F2, 0xFF4DA3FF, 0xFFA77BFF, 0xFFFF5A6A, 0xFFFFB040, 0xFFFF4DDB, 0xFFFF7A59, 0xFF4DFFC3};
	static final int[] ACCENT2 = {0xFF9DB4FF, 0xFF4DFFE0, 0xFF6BA8FF, 0xFFFFA05A, 0xFFFF5A8A, 0xFF4DE1FF, 0xFFB06BFF, 0xFF4DA3FF};
	static int theme = 0;

	public static int accent() {
		return ACCENT[Math.floorMod(theme, ACCENT.length)];
	}

	public static int accent2() {
		return ACCENT2[Math.floorMod(theme, ACCENT2.length)];
	}

	// ---- палитра цветов для настроек ----
	static final String[] CNAMES = {"Белый", "Красный", "Оранжевый", "Жёлтый", "Зелёный", "Голубой", "Синий", "Тёмно-синий", "Фиолетовый", "Розовый", "Радуга"};
	static final int[] CRGB = {0xFFFFFF, 0xFF4B4B, 0xFF9A3C, 0xFFE14D, 0x5CFF7C, 0x4DE1FF, 0x4D7CFF, 0x1B2A6B, 0xB06BFF, 0xFF7BD1, 0};
	static final int RAINBOW = CRGB.length - 1;

	// ---- типы настроек ----
	public abstract static class Setting {
		final String id, name;
		double d0; // значение по умолчанию (для кнопки «Сброс»)

		Setting(String id, String name) { this.id = id; this.name = name; }
		abstract double get();
		abstract void set(double v);
		void reset() { set(d0); }
	}

	public static final class Bool extends Setting {
		public boolean v;
		Bool(String id, String name, boolean def) { super(id, name); v = def; d0 = get(); }
		double get() { return v ? 1 : 0; }
		void set(double d) { v = d >= 0.5; }
	}

	public static final class Slider extends Setting {
		final double min, max, step;
		final String unit;
		public double v;
		Slider(String id, String name, double min, double max, double step, double def, String unit) {
			super(id, name); this.min = min; this.max = max; this.step = step; this.v = def; this.unit = unit; d0 = get();
		}
		double get() { return v; }
		void set(double d) { v = Math.max(min, Math.min(max, Math.round(d / step) * step)); }
	}

	/** Цвет: ползунки R/G/B или радуга. В файл пишется одним числом (-1 = радуга). */
	public static final class Color extends Setting {
		public int idx; // RAINBOW или 0 (свой цвет)
		final Slider sr = new Slider("r", "R", 0, 255, 1, 255, "");
		final Slider sg = new Slider("g", "G", 0, 255, 1, 255, "");
		final Slider sb = new Slider("b", "B", 0, 255, 1, 255, "");

		Color(String id, String name, int def) {
			super(id, name);
			fromIdx(def);
			d0 = get();
		}

		private void fromIdx(int d) {
			if (d == RAINBOW) { idx = RAINBOW; return; }
			idx = 0;
			setCustom(CRGB[Math.floorMod(d, CRGB.length - 1)]);
		}

		private void setCustom(int c) {
			sr.v = (c >> 16) & 255;
			sg.v = (c >> 8) & 255;
			sb.v = c & 255;
		}

		/** Выбрать готовый цвет из палитры (выключает радугу). */
		void pick(int rgb) {
			idx = 0;
			setCustom(rgb);
		}

		private int custom() { return ((int) sr.v << 16) | ((int) sg.v << 8) | (int) sb.v; }

		double get() { return idx == RAINBOW ? -1 : 0x1000000 + custom(); }

		void set(double d) {
			if (d < 0) idx = RAINBOW;
			else if (d >= 0x1000000) { idx = 0; setCustom((int) d - 0x1000000); }
			else fromIdx((int) d); // старый формат (номер цвета из палитры)
		}

		/** RGB (без альфы); радуга считается по времени. */
		public int rgb() {
			if (idx != RAINBOW) return custom();
			return hsv((System.currentTimeMillis() % 4000L) / 4000f, 0.65f, 1f);
		}
	}

	public static final class Choice extends Setting {
		String[] opts;
		public int idx;
		Choice(String id, String name, int def, String... opts) { super(id, name); this.opts = opts; this.idx = def; d0 = get(); }
		double get() { return idx; }
		void set(double d) { idx = Math.floorMod((int) d, opts.length); }
	}

	public static final class Module {
		final String id, name, desc, cat;
		final List<Setting> settings = new ArrayList<>();
		public boolean enabled;
		public int key;       // клавиша-переключатель (GLFW), 0 - не назначена
		boolean keyDown;
		Runnable apply = () -> { };
		Module(String id, String name, String desc, String cat, boolean def, Setting... s) {
			this.id = id; this.name = name; this.desc = desc; this.cat = cat; this.enabled = def;
			for (Setting x : s) settings.add(x);
		}
		Module apply(Runnable r) { this.apply = r; return this; }
	}

	static final String VIS = "Visuals", HUD = "HUD";

	// ---- 1. Target ESP (только вращающийся ромб как в Nursultan, без настроек) ----
	public static final Module TARGET_ESP = new Module("target_esp", "Target ESP", "Ромб на цели боя (как в Nursultan)", VIS, true);

	// ---- 2. Custom Sky (только выбор неба) ----
	public static final Choice SKY_PRESET = new Choice("preset", "Небо", 0, SkyLib.names());
	public static final Module SKY = new Module("sky", "Custom Sky", "Своё небо: без солнца, луны и облаков", VIS, false, SKY_PRESET);

	// ---- 3. FPS (просто счётчик) ----
	public static final Module FPS = new Module("fps", "FPS", "Счётчик кадров в секунду", HUD, true);

	// ---- 4. HP противника под прицелом ----
	public static final Choice AH_STYLE = new Choice("style", "Вид", 0, "Панель", "Цифры под прицелом");
	public static final Module AIMHP = new Module("aimhp", "Enemy HP", "Здоровье противника под прицелом", HUD, true, AH_STYLE);

	// ---- 5. Armor Status ----
	public static final Bool AR_DUR = new Bool("dur", "Прочность в процентах", true);
	public static final Bool AR_VERT = new Bool("vert", "Столбиком (иначе в ряд)", true);
	public static final Module ARMOR = new Module("armor", "Armor Status", "Броня и её прочность", HUD, true, AR_DUR, AR_VERT);

	// ---- оформление интерфейса (вкладка «Тема») ----
	public static final Slider UI_OPACITY = new Slider("opacity", "Прозрачность плашек", 30, 100, 5, 72, "%");
	public static final Slider UI_ROUND = new Slider("round", "Скругление", 0, 8, 1, 4, " px");
	public static final Bool UI_GLOW = new Bool("glow", "Тени и свечение", true);
	public static final Bool UI_PART = new Bool("particles", "Частицы в меню", true);
	public static final Module STYLE = new Module("style", "Интерфейс", "", "UI", true, UI_OPACITY, UI_ROUND, UI_GLOW, UI_PART);

	static final List<Module> ALL = List.of(TARGET_ESP, SKY, FPS, AIMHP, ARMOR);

	static Options opts() { return Minecraft.getInstance().options; }

	static int hsv(float h, float s, float v) {
		float r = 0, g = 0, b = 0;
		float f = (h - (float) Math.floor(h)) * 6f;
		int i = (int) f;
		float ff = f - i, p = v * (1 - s), q = v * (1 - s * ff), t = v * (1 - s * (1 - ff));
		switch (i) {
			case 0 -> { r = v; g = t; b = p; }
			case 1 -> { r = q; g = v; b = p; }
			case 2 -> { r = p; g = v; b = t; }
			case 3 -> { r = p; g = q; b = v; }
			case 4 -> { r = t; g = p; b = v; }
			default -> { r = v; g = p; b = q; }
		}
		return ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
	}

	/** Название клавиши для интерфейса. */
	static String keyName(int k) {
		if (k <= 0) return "—";
		String s = switch (k) {
			case GLFW.GLFW_KEY_SPACE -> "Space";
			case GLFW.GLFW_KEY_LEFT_SHIFT -> "LShift";
			case GLFW.GLFW_KEY_RIGHT_SHIFT -> "RShift";
			case GLFW.GLFW_KEY_LEFT_CONTROL -> "LCtrl";
			case GLFW.GLFW_KEY_RIGHT_CONTROL -> "RCtrl";
			case GLFW.GLFW_KEY_LEFT_ALT -> "LAlt";
			case GLFW.GLFW_KEY_RIGHT_ALT -> "RAlt";
			case GLFW.GLFW_KEY_TAB -> "Tab";
			case GLFW.GLFW_KEY_ENTER -> "Enter";
			case GLFW.GLFW_KEY_UP -> "Up";
			case GLFW.GLFW_KEY_DOWN -> "Down";
			case GLFW.GLFW_KEY_LEFT -> "Left";
			case GLFW.GLFW_KEY_RIGHT -> "Right";
			case GLFW.GLFW_KEY_INSERT -> "Insert";
			case GLFW.GLFW_KEY_HOME -> "Home";
			case GLFW.GLFW_KEY_END -> "End";
			case GLFW.GLFW_KEY_PAGE_UP -> "PgUp";
			case GLFW.GLFW_KEY_PAGE_DOWN -> "PgDn";
			case GLFW.GLFW_KEY_CAPS_LOCK -> "Caps";
			default -> null;
		};
		if (s != null) return s;
		if (k >= GLFW.GLFW_KEY_F1 && k <= GLFW.GLFW_KEY_F12) return "F" + (k - GLFW.GLFW_KEY_F1 + 1);
		String n = null;
		try {
			n = GLFW.glfwGetKeyName(k, 0);
		} catch (Throwable ignored) {
		}
		return n != null ? n.toUpperCase() : "K" + k;
	}

	/** Применить изменение модуля: опции игры, цвет удара и сохранение. */
	static void changed(Module m) {
		try {
			m.apply.run();
		} catch (Throwable ignored) {
		}
		save();
	}

	/** Переключить модуль (меню, клавиша); с клавиши - ещё и всплывашка. */
	static void toggle(Module m) {
		m.enabled = !m.enabled;
		changed(m);
	}

	static void load() {
		try {
			if (!Files.exists(FILE)) return;
			JsonObject o = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
			if (o.has("theme")) theme = Math.floorMod(o.get("theme").getAsInt(), THEMES.length);
			JsonObject mods = o.has("modules") ? o.getAsJsonObject("modules") : new JsonObject();
			for (Module m : ALL) {
				if (!mods.has(m.id)) continue;
				JsonObject mo = mods.getAsJsonObject(m.id);
				if (mo.has("on")) m.enabled = mo.get("on").getAsBoolean();
				if (mo.has("key")) m.key = mo.get("key").getAsInt();
				for (Setting s : m.settings) {
					if (mo.has(s.id)) s.set(mo.get(s.id).getAsDouble());
				}
			}
			if (o.has("style")) {
				JsonObject so = o.getAsJsonObject("style");
				for (Setting s : STYLE.settings) {
					if (so.has(s.id)) s.set(so.get(s.id).getAsDouble());
				}
			}
		} catch (Exception ignored) {
		}
	}

	static void save() {
		try {
			JsonObject o = new JsonObject();
			o.addProperty("theme", theme);
			JsonObject mods = new JsonObject();
			for (Module m : ALL) {
				JsonObject mo = new JsonObject();
				mo.addProperty("on", m.enabled);
				mo.addProperty("key", m.key);
				for (Setting s : m.settings) mo.addProperty(s.id, s.get());
				mods.add(m.id, mo);
			}
			o.add("modules", mods);
			JsonObject so = new JsonObject();
			for (Setting s : STYLE.settings) so.addProperty(s.id, s.get());
			o.add("style", so);
			Files.writeString(FILE, o.toString());
		} catch (Exception ignored) {
		}
	}
}
