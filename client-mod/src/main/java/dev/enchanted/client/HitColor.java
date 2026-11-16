package dev.enchanted.client;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.renderer.texture.DynamicTexture;

/** Цвет подсветки сущности при ударе: перекрашиваем «красную» половину текстуры наложения. */
public final class HitColor {
	static DynamicTexture tex;

	static boolean abgr; // формат пикселей NativeImage в этой версии
	private static int n;

	public static void attach(DynamicTexture t) {
		tex = t;
		try {
			NativeImage img = t.getPixels();
			if (img != null) {
				int p = img.getPixel(0, 0); // в ванили здесь красный
				abgr = (p & 0xFF) == 0xFF && ((p >> 16) & 0xFF) == 0;
			}
		} catch (Throwable ignored) {
		}
		refresh();
	}

	/** Радужный цвет удара обновляется каждые 3 тика. */
	public static void tick() {
		if (Cfg.HIT.enabled && Cfg.HIT_COLOR.idx == Cfg.RAINBOW && ++n % 3 == 0) refresh();
	}

	public static void refresh() {
		try {
			DynamicTexture t = tex;
			if (t == null) return;
			NativeImage img = t.getPixels();
			if (img == null) return;
			int argb = 0xB2FF0000; // ваниль: красный, 70%
			if (Cfg.HIT.enabled) {
				int a = (int) Math.round(255 * Cfg.HIT_ALPHA.v / 100.0);
				argb = (a << 24) | (Cfg.HIT_COLOR.rgb() & 0xFFFFFF);
			}
			if (abgr) argb = (argb & 0xFF00FF00) | ((argb & 0xFF) << 16) | ((argb >> 16) & 0xFF);
			int h = img.getHeight() / 2;
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < img.getWidth(); x++) {
					img.setPixel(x, y, argb);
				}
			}
			t.upload();
		} catch (Throwable ignored) {
		}
	}
}
