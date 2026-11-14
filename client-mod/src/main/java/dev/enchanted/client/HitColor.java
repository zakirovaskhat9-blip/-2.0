package dev.enchanted.client;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.renderer.texture.DynamicTexture;

/** Цвет подсветки сущности при ударе: перекрашиваем «красную» половину текстуры наложения. */
public final class HitColor {
	static DynamicTexture tex;

	public static void attach(DynamicTexture t) {
		tex = t;
		refresh();
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
