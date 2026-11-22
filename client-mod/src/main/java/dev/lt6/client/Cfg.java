package dev.lt6.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/** Настройки клиента: модули, их параметры, клавиши, позиции HUD, сохранение в config/lt6.json. */
public final class Cfg {
    private static final Logger LOGGER = LoggerFactory.getLogger("LT6/Config");
	static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("lt6.json");

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
			if (d >= 0x1000000) { setCustom(d - 0x1000000); return; }
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

	static final String VIS = "Visuals", HUD = "Utils", PLAYER = "Player";

	// LT6 — all new visuals, no legacy modules.
	public static final Choice ESP_STYLE = new Choice("style", "Стиль", 0, "Стандарт", "Нурсултан ромб", "Целестиал духи");
	public static final Slider ESP_TURN_SECONDS = new Slider("spin_period", "Скорость вращения", 0.15, 1.2, 0.05, 0.25, "с/об");
	public static final Slider ESP_SIZE = new Slider("esp_size", "Размер ESP", 0.5, 20.0, 0.05, 1.0, "x");
	public static final Color ESP_TINT = new Color("esp_color", "Цвет ESP", 0x1000000 + 0x39FF14);
	public static final Color ESP_DIAMOND_TINT = new Color("diamond_color", "Цвет ромба", 0x1000000 + 0xB6F0FD);
	public static final Slider ESP_DIAMOND_GLOW = new Slider("diamond_glow", "Свечение ромба", 0, 200, 10, 0, "%");
	public static final Slider ESP_GLOW = new Slider("esp_glow", "Сила свечения", 0, 200, 10, 0, "%");
	public static final Bool ESP_RED_ON_HIT = new Bool("red_on_hit", "Покраснение при ударе", false);
	public static final Module TARGET_ESP = new Module("target_esp", "Target ESP", "Три стиля: Стандарт, Нурсултан ромб и Целестиал духи", VIS, true, ESP_STYLE, ESP_TURN_SECONDS, ESP_SIZE, ESP_TINT, ESP_DIAMOND_TINT, ESP_DIAMOND_GLOW, ESP_GLOW, ESP_RED_ON_HIT);

	public static final Choice SKY_PRESET = new Choice("preset", "Небо", 0, SkyLib.names());
	public static final Module SKY = new Module("sky", "Custom Sky", "Шесть небес, переключение без перезапуска", VIS, false, SKY_PRESET);

	// Register the callback after SKY has been assigned: no self-reference in its initializer.
	static {
		SKY.apply(() -> SkyPacks.select(SKY_PRESET.idx, SKY.enabled));
	}

	public static final Slider FPS_SIZE = new Slider("size", "Размер FPS", 0.5, 4.0, 0.1, 1.0, "x");
	public static final Module FPS = new Module("fps", "FPS Counter", "Счётчик FPS с настраиваемым размером", HUD, true, FPS_SIZE);
	public static final Module PING = new Module("ping", "Ping Counter", "Текущий пинг до сервера в миллисекундах", HUD, false);

	public static final Slider HEARTS_SIZE = new Slider("size", "Размер сердец", 0.6, 2.0, 0.1, 1.0, "x");
	public static final Module ENEMY_HEARTS = new Module("enemy_hearts", "Enemy Hearts", "Здоровье игроков над головой, с учётом поглощения", VIS, false, HEARTS_SIZE);

	// Руки: основной и второй руке можно задать собственную позицию и размер.
	// Смещения в единицах пространства рендера первого лица (не блоки мира).
	public static final Slider MAIN_HAND_SIZE = new Slider("main_scale", "Основная: размер руки", 0.3, 2.5, 0.05, 1.0, "x");
	public static final Slider MAIN_HAND_X = new Slider("main_x", "Основная: X", -1.5, 1.5, 0.05, 0, "");
	public static final Slider MAIN_HAND_Y = new Slider("main_y", "Основная: Y", -1.5, 1.5, 0.05, 0, "");
	public static final Slider MAIN_HAND_Z = new Slider("main_z", "Основная: Z", -1.5, 1.5, 0.05, 0, "");
	public static final Slider OFF_HAND_SIZE = new Slider("off_scale", "Вторая: размер руки", 0.3, 2.5, 0.05, 1.0, "x");
	public static final Slider OFF_HAND_X = new Slider("off_x", "Вторая: X", -1.5, 1.5, 0.05, 0, "");
	public static final Slider OFF_HAND_Y = new Slider("off_y", "Вторая: Y", -1.5, 1.5, 0.05, 0, "");
	public static final Slider OFF_HAND_Z = new Slider("off_z", "Вторая: Z", -1.5, 1.5, 0.05, 0, "");
	public static final Module HAND_VIEW = new Module("hand_view", "Viewmodel Hands", "Положение и масштаб рук от первого лица", VIS, false,
		MAIN_HAND_SIZE, MAIN_HAND_X, MAIN_HAND_Y, MAIN_HAND_Z, OFF_HAND_SIZE, OFF_HAND_X, OFF_HAND_Y, OFF_HAND_Z);

	// Дополнительные преобразования только модели предмета, независимо от общей руки.
	public static final Slider ITEM_VIEW_SIZE = new Slider("item_scale", "Размер предмета", 0.3, 2.5, 0.05, 1.0, "x");
	public static final Slider ITEM_VIEW_X = new Slider("item_x", "Предмет: X", -1.5, 1.5, 0.05, 0, "");
	public static final Slider ITEM_VIEW_Y = new Slider("item_y", "Предмет: Y", -1.5, 1.5, 0.05, 0, "");
	public static final Slider ITEM_VIEW_Z = new Slider("item_z", "Предмет: Z", -1.5, 1.5, 0.05, 0, "");
	public static final Module ITEM_VIEW = new Module("item_view", "Viewmodel Items", "Независимый размер и положение предметов в руках", VIS, false,
		ITEM_VIEW_SIZE, ITEM_VIEW_X, ITEM_VIEW_Y, ITEM_VIEW_Z);

	public static final Color HIT_TINT = new Color("color", "Цвет попадания", 1);
	public static final Slider HIT_SIZE = new Slider("size", "Размер частиц", 0.5, 2.5, 0.1, 1.0, "x");
	public static final Bool HIT_GLOW = new Bool("glow", "Свечение модели", false);
	public static final Slider HIT_GLOW_STRENGTH = new Slider("glow_strength", "Интенсивность свечения", 0, 200, 10, 100, "%");
	public static final Module HIT_COLOR = new Module("hit_color", "Hit Color", "Однотонный цвет игрока и брони при ударе", VIS, false, HIT_TINT, HIT_GLOW, HIT_GLOW_STRENGTH, HIT_SIZE);

	public static final Slider WARMTH = new Slider("warmth", "Температура цвета", -100, 100, 5, 0, "%");
	public static final Module FILTER = new Module("filter", "Color Filter", "Шейдер тёплых/холодных оттенков", VIS, false, WARMTH);

	public static final Slider CONTRAST_POWER = new Slider("contrast", "Контраст", -100, 100, 5, 0, "%");
	public static final Module CONTRAST = new Module("contrast", "Contrast", "Нужен полноценный shader для изменения насыщенности", VIS, false, CONTRAST_POWER);

	public static final Slider BRIGHTNESS_LEVEL = new Slider("level", "Яркость", 0.2, 2.4, 0.1, 1.0, "x");
	public static final Module BRIGHTNESS = new Module("brightness", "Brightness", "Уровень гаммы", VIS, false, BRIGHTNESS_LEVEL);

	public static final Color JUMP_TINT = new Color("color", "Цвет круга", 5);
	public static final Slider JUMP_RADIUS = new Slider("radius", "Радиус", 0.6, 2.5, 0.1, 1.2, "м");
	public static final Module JUMP_CIRCLES = new Module("jump_circles", "Jump Circles", "Цветная волна при прыжке и приземлении", PLAYER, false, JUMP_TINT, JUMP_RADIUS);

	static final Slider UI_OPACITY = new Slider("opacity", "Прозрачность плашек", 30, 100, 5, 72, "%");
	static final Slider UI_ROUND = new Slider("round", "Скругление", 0, 8, 1, 4, " px");
	static final Bool UI_GLOW = new Bool("glow", "Тени и свечение", false);
	static final Bool UI_PART = new Bool("particles", "Частицы в меню", false);
	static final Bool UI_BLUR = new Bool("blur", "Размытие меню", false);
	static final Module STYLE = new Module("style", "Интерфейс", "", "UI", true, UI_OPACITY, UI_ROUND, UI_GLOW, UI_PART, UI_BLUR);


    // Live GPU colour grading. Percentage sliders are applied independently;
    // changing any slider switches from a preset to Custom.
    public static final Choice GRADING_PRESET = new Choice("preset", "Режим", 0,
            "Обычный", "Яркий", "Тёплый", "Холодный", "Сочный", "Мягкий",
            "Кино", "Pulse", "Lunar", "Свой");
    public static final Slider GRADE_BRIGHTNESS = new Slider("brightness", "Яркость", 0.5, 2.0, 0.05, 1.0, "x");
    public static final Slider GRADE_CONTRAST = new Slider("contrast", "Контраст", 0.5, 2.0, 0.05, 1.0, "x");
    public static final Slider GRADE_WARMTH = new Slider("warmth", "Теплота", -100, 100, 5, 0, "%");
    public static final Slider GRADE_SATURATION = new Slider("saturation", "Насыщенность", 0, 200, 5, 100, "%");
    public static final Slider GRADE_VIBRANCE = new Slider("vibrance", "Сочность", 0, 200, 5, 100, "%");
    public static final Slider GRADE_INTENSITY = new Slider("intensity", "Сила эффекта", 0, 100, 5, 100, "%");
    public static final Module GRADING = new Module("color_grading", "Color Grading",
            "GPU-коррекция цвета в реальном времени", VIS, false,
            GRADING_PRESET, GRADE_BRIGHTNESS, GRADE_CONTRAST, GRADE_WARMTH,
            GRADE_SATURATION, GRADE_VIBRANCE, GRADE_INTENSITY);

    /** Apply one complete preset. The final "Свой" entry keeps manual values. */
    static void gradingPreset(int mode) {
        int index = Math.max(0, Math.min(GRADING_PRESET.opts.length - 1, mode));
        GRADING_PRESET.idx = index;
        if (index == GRADING_PRESET.opts.length - 1) return;
        double[][] modes = {
                {1.00, 1.00,   0, 100, 100, 100}, // Обычный
                {1.20, 1.12,   5, 120, 110, 100}, // Яркий
                {1.07, 1.13,  55, 113, 108, 100}, // Тёплый
                {1.06, 1.14, -60, 115, 108, 100}, // Холодный
                {1.07, 1.19,  10, 160, 138, 100}, // Сочный
                {1.05, 0.85,   0,  88,  84, 100}, // Мягкий
                {0.92, 1.30, -10,  90,  95, 100}, // Кино
                {1.15, 1.30,  15, 170, 140, 100}, // Pulse
                {1.10, 1.11,   0, 125, 115, 100}  // Lunar
        };
        double[] settings = modes[index];
        Slider[] sliders = {
                GRADE_BRIGHTNESS, GRADE_CONTRAST, GRADE_WARMTH,
                GRADE_SATURATION, GRADE_VIBRANCE, GRADE_INTENSITY
        };
        for (int i = 0; i < sliders.length; i++) sliders[i].set(settings[i]);
    }

    // Other older visuals remain disabled; new Color Grading has its own tab.
    static final List<Module> ALL = List.of(FPS, TARGET_ESP, SKY, GRADING);

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
		} catch (RuntimeException error) {
            LOGGER.error("Failed to apply module settings: {}", m.id, error);
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
		} catch (Exception error) {
            LOGGER.warn("Cannot read LT6 configuration {}. Defaults will be used.", FILE, error);
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
            Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, o.toString());
		} catch (Exception error) {
            LOGGER.error("Could not save LT6 configuration to {}", FILE, error);
		}
	}
}
