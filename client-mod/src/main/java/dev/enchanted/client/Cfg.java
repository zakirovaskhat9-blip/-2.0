package dev.enchanted.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/** Настройки клиента: модули, их параметры, сохранение в config/enchanted-client.json. */
public final class Cfg {
	static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("enchanted-client.json");

	// ---- темы интерфейса ----
	static final String[] THEMES = {"Белый", "Океан", "Аметист", "Рубин", "Янтарь"};
	static final int[] ACCENT = {0xFFF2F2F2, 0xFF4DA3FF, 0xFFA77BFF, 0xFFFF5A6A, 0xFFFFB040};
	static int theme = 0;

	// ---- палитра цветов для настроек ----
	static final String[] CNAMES = {"Белый", "Красный", "Оранжевый", "Жёлтый", "Зелёный", "Голубой", "Синий", "Тёмно-синий", "Фиолетовый", "Розовый", "Радуга"};
	static final int[] CRGB = {0xFFFFFF, 0xFF4B4B, 0xFF9A3C, 0xFFE14D, 0x5CFF7C, 0x4DE1FF, 0x4D7CFF, 0x1B2A6B, 0xB06BFF, 0xFF7BD1, 0};
	static final int RAINBOW = CRGB.length - 1;

	// ---- типы настроек ----
	public abstract static class Setting {
		final String id, name;
		Setting(String id, String name) { this.id = id; this.name = name; }
		abstract double get();
		abstract void set(double v);
	}

	public static final class Bool extends Setting {
		public boolean v;
		Bool(String id, String name, boolean def) { super(id, name); v = def; }
		double get() { return v ? 1 : 0; }
		void set(double d) { v = d >= 0.5; }
	}

	public static final class Slider extends Setting {
		final double min, max, step;
		final String unit;
		public double v;
		Slider(String id, String name, double min, double max, double step, double def, String unit) {
			super(id, name); this.min = min; this.max = max; this.step = step; this.v = def; this.unit = unit;
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
		Choice(String id, String name, int def, String... opts) { super(id, name); this.opts = opts; this.idx = def; }
		double get() { return idx; }
		void set(double d) { idx = Math.floorMod((int) d, opts.length); }
	}

	public static final class Module {
		final String id, name, desc, cat;
		final List<Setting> settings = new ArrayList<>();
		public boolean enabled;
		Runnable apply = () -> { };
		Module(String id, String name, String desc, String cat, boolean def, Setting... s) {
			this.id = id; this.name = name; this.desc = desc; this.cat = cat; this.enabled = def;
			for (Setting x : s) settings.add(x);
		}
		Module apply(Runnable r) { this.apply = r; return this; }
	}

	static final String VIS = "Visuals", HUD = "HUD", REN = "Render";

	// ---- Target ESP ----
	public static final Choice ESP_STYLE = new Choice("style", "Стиль", 1, "Рамка", "Углы", "Заливка", "Скобки", "Ромб", "Кольцо", "Орбита", "Стрелка", "Перекрестие");
	public static final Color ESP_COLOR = new Color("color", "Цвет", RAINBOW);
	public static final Bool ESP_HP = new Bool("hp", "Полоска здоровья", true);
	public static final Slider ESP_HPW = new Slider("hpw", "Толщина здоровья", 1, 8, 1, 2, " px");
	public static final Bool ESP_NAME = new Bool("name", "Ник цели", true);
	public static final Slider ESP_TIME = new Slider("time", "Держать после удара", 1, 10, 1, 4, " с");
	public static final Bool ESP_ANIM = new Bool("anim", "Анимация (пульс)", true);
	public static final Bool ESP_AIM = new Bool("aim", "Также при наведении", false);
	public static final Module TARGET_ESP = new Module("target_esp", "Target ESP", "Метка на цели боя", VIS, true,
			ESP_STYLE, ESP_COLOR, ESP_ANIM, ESP_HP, ESP_HPW, ESP_NAME, ESP_TIME, ESP_AIM);

	// ---- Hit Color ----
	public static final Color HIT_COLOR = new Color("color", "Цвет удара", 1);
	public static final Slider HIT_ALPHA = new Slider("alpha", "Насыщенность", 20, 100, 5, 70, "%");
	public static final Module HIT = new Module("hitcolor", "Hit Color", "Цвет подсветки при ударе", VIS, true, HIT_COLOR, HIT_ALPHA)
			.apply(() -> HitColor.refresh());

	// ---- Custom Sky ----
	public static final Choice SKY_PRESET = new Choice("preset", "Небо", 0, SkyLib.names());
	public static final Color SKY_COLOR = new Color("sky", "Цвет неба", 8);
	public static final Bool SKY_FOG = new Bool("fog", "Туман в цвет неба", true);
	public static final Color SKY_CLOUD = new Color("cloud", "Цвет облаков", 9);
	public static final Bool SKY_STARS = new Bool("stars_on", "Звёзды (3D)", false);
	public static final Module SKY = new Module("sky", "Custom Sky", "Готовые и свои небеса, анимация", VIS, false,
			SKY_PRESET, SKY_COLOR, SKY_FOG, SKY_CLOUD, SKY_STARS);

	// ---- HUD-модули ----
	public static final Color OV_COLOR = new Color("color", "Цвет HUD", RAINBOW);
	public static final Bool WM_FPS = new Bool("fps", "FPS в плашке", true);
	public static final Bool WM_TIME = new Bool("time", "Время в плашке", true);
	public static final Module WM = new Module("watermark", "Watermark", "Плашка с FPS и временем", HUD, true, OV_COLOR, WM_FPS, WM_TIME);
	public static final Bool I_FPS = new Bool("fps", "FPS", true);
	public static final Bool I_XYZ = new Bool("xyz", "Координаты", true);
	public static final Bool I_SPEED = new Bool("speed", "Скорость", true);
	public static final Bool I_DIR = new Bool("dir", "Курс", true);
	public static final Module INFO = new Module("info", "Info HUD", "Координаты, скорость, курс", HUD, false, I_FPS, I_XYZ, I_SPEED, I_DIR);
	public static final Bool K_CPS = new Bool("cps", "Показывать CPS", true);
	public static final Module KEYS = new Module("keys", "Keystrokes", "WASD, пробел, мышь и CPS", HUD, false, K_CPS);
	public static final Module ARRAY = new Module("arraylist", "ArrayList", "Список включённых модулей", HUD, false);

	public static final Module THUD = new Module("targethud", "Target HUD", "Карточка цели: ник и здоровье", HUD, false);
	public static final Module ARMOR = new Module("armorhud", "Armor HUD", "Броня и её прочность", HUD, false);
	public static final Module EFFECTS = new Module("effects", "Effects", "Активные эффекты и время", HUD, false);
	public static final Color HM_COLOR = new Color("color", "Цвет метки", 0);
	public static final Slider HM_SIZE = new Slider("size", "Размер", 3, 12, 1, 6, " px");
	public static final Module HITMARK = new Module("hitmarker", "Hit Marker", "Метка у прицела при попадании", HUD, false, HM_COLOR, HM_SIZE);
	public static final Slider Z_FOV = new Slider("fov", "Приближение (FOV)", 30, 90, 1, 30, "");
	public static final Module ZOOM = new Module("zoom", "Zoom", "Зажми C - приблизить", VIS, false, Z_FOV);

	// ---- обычные настройки ----
	public static final Slider FOV_V = new Slider("fov", "FOV", 30, 110, 1, 90, "");
	public static final Module FOV = new Module("fov", "FOV", "Угол обзора", VIS, false, FOV_V).apply(() -> {
		if (Cfg.FOV.enabled) opts().fov().set((int) FOV_V.v);
	});
	public static final Slider BR_V = new Slider("level", "Яркость", 0, 100, 5, 100, "%");
	public static final Module BRIGHT = new Module("bright", "Яркость", "Яркость мира", VIS, false, BR_V).apply(() -> {
		if (Cfg.BRIGHT.enabled) opts().gamma().set(BR_V.v / 100.0);
	});
	public static final Module BOB = new Module("bob", "Покачивание", "Покачивание камеры при ходьбе", VIS, true).apply(() -> {
		opts().bobView().set(Cfg.BOB.enabled);
	});

	public static final Slider RD_V = new Slider("dist", "Блоков (чанков)", 2, 32, 1, 12, "");
	public static final Module RD = new Module("rd", "Дальность", "Дальность прорисовки", REN, false, RD_V).apply(() -> {
		if (Cfg.RD.enabled) opts().renderDistance().set((int) RD_V.v);
	});
	public static final Slider FPS_V = new Slider("fps", "Макс. FPS", 30, 260, 10, 260, "");
	public static final Module FPS = new Module("fps", "Лимит FPS", "260 = без лимита", REN, false, FPS_V).apply(() -> {
		if (Cfg.FPS.enabled) opts().framerateLimit().set((int) FPS_V.v);
	});
	public static final Module VSYNC = new Module("vsync", "VSync", "Вертикальная синхронизация", REN, false).apply(() -> {
		opts().enableVsync().set(Cfg.VSYNC.enabled);
	});

	static final List<Module> ALL = List.of(TARGET_ESP, HIT, SKY, WM, INFO, KEYS, ARRAY, THUD, ARMOR, EFFECTS, HITMARK, ZOOM, FOV, BRIGHT, BOB, RD, FPS, VSYNC);

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

	/** Применить изменение модуля: опции игры, цвет удара и сохранение. */
	static void changed(Module m) {
		try {
			m.apply.run();
		} catch (Throwable ignored) {
		}
		save();
	}

	/** Подтянуть текущие значения из настроек игры (чтобы ползунки стартовали с реальных). */
	static void syncFromOptions() {
		try {
			Options o = opts();
			if (!FOV.enabled) FOV_V.set(o.fov().get());
			if (!BRIGHT.enabled) BR_V.set(o.gamma().get() * 100.0);
			if (!RD.enabled) RD_V.set(o.renderDistance().get());
			if (!FPS.enabled) FPS_V.set(o.framerateLimit().get());
			BOB.enabled = o.bobView().get();
			VSYNC.enabled = o.enableVsync().get();
		} catch (Throwable ignored) {
		}
	}

	static void applyAll() {
		for (Module m : ALL) {
			try {
				m.apply.run();
			} catch (Throwable ignored) {
			}
		}
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
				for (Setting s : m.settings) {
					if (mo.has(s.id)) s.set(mo.get(s.id).getAsDouble());
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
				for (Setting s : m.settings) mo.addProperty(s.id, s.get());
				mods.add(m.id, mo);
			}
			o.add("modules", mods);
			Files.writeString(FILE, o.toString());
		} catch (Exception ignored) {
		}
	}
}
