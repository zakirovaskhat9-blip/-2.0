package dev.enchanted.client;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;

/** Рисует небо-куб из картинок через ванильный CubeMap (панорама главного меню). Всё через reflection: при несовпадении API тихо отключается. */
public final class SkyPano {
	private static final Map<String, Object> MAPS = new HashMap<>();
	private static boolean dead;
	private static Method render;

	private static Object id(String path) throws Exception {
		Class<?> c;
		try {
			c = Class.forName("net.minecraft.resources.Identifier");
		} catch (ClassNotFoundException e) {
			c = Class.forName("net.minecraft.resources.ResourceLocation");
		}
		return c.getMethod("fromNamespaceAndPath", String.class, String.class).invoke(null, "enchanted_client", path);
	}

	/** Вызывать из рендера неба. true - небо нарисовано. */
	public static boolean draw(Minecraft mc) {
		if (dead || !Cfg.SKY.enabled) return false;
		SkyLib.Def d = SkyLib.current(Cfg.SKY_PRESET.idx);
		if (d == null || d.pano == null || mc.gameRenderer == null) return false;
		try {
			Object cm = MAPS.get(d.pano);
			if (cm == null) {
				Object base = id("textures/gui/sky/" + d.pano);
				Constructor<?> k = null;
				for (Constructor<?> c : Class.forName("net.minecraft.client.renderer.CubeMap").getConstructors()) {
					if (c.getParameterCount() == 1) k = c;
				}
				cm = k.newInstance(base);
				MAPS.put(d.pano, cm);
				try {
					cm.getClass().getMethod("registerTextures", Class.forName("net.minecraft.client.renderer.texture.TextureManager"))
							.invoke(cm, mc.getTextureManager());
				} catch (Exception ignored) {
				}
			}
			if (render == null) {
				for (Method m : cm.getClass().getMethods()) {
					if (m.getName().equals("render") && m.getParameterCount() >= 3 && m.getParameterTypes()[0] == Minecraft.class) render = m;
				}
			}
			var cam = mc.gameRenderer.getMainCamera();
			Object[] args = render.getParameterCount() == 3
					? new Object[] {mc, cam.xRot(), cam.yRot() + 180f}
					: new Object[] {mc, cam.xRot(), cam.yRot() + 180f, 1f};
			render.invoke(cm, args);
			return true;
		} catch (Throwable t) {
			dead = true;
			return false;
		}
	}
}
