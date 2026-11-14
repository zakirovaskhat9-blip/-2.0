package dev.enchanted.client;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

/** Target ESP: 2D-метка вокруг цели боя (без просвечивания сквозь стены). */
public final class Hud {
	private static LivingEntity target;
	private static long until;

	static void hit(Entity e) {
		if (e instanceof LivingEntity le) {
			target = le;
			until = System.currentTimeMillis() + (long) (Cfg.ESP_TIME.v * 1000);
		}
	}

	public static void render(GuiGraphics g, DeltaTracker dt) {
		Minecraft mc = Minecraft.getInstance();
		if (!Cfg.TARGET_ESP.enabled || mc.player == null || mc.level == null || mc.options.hideGui) return;
		long now = System.currentTimeMillis();
		if (Cfg.ESP_AIM.v && mc.crosshairPickEntity instanceof LivingEntity le && le != mc.player) {
			target = le;
			until = Math.max(until, now + 400);
		}
		LivingEntity t = target;
		if (t == null || now > until || !t.isAlive() || t.isRemoved() || t == mc.player) return;

		Camera cam = mc.gameRenderer.getMainCamera();
		if (cam == null || !cam.isInitialized()) return;
		float pt = dt.getGameTimeDeltaPartialTick(false);
		int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();

		double ox = t.xo + (t.getX() - t.xo) * pt - t.getX();
		double oy = t.yo + (t.getY() - t.yo) * pt - t.getY();
		double oz = t.zo + (t.getZ() - t.zo) * pt - t.getZ();
		AABB b = t.getBoundingBox().move(ox, oy, oz);

		double x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
		for (int i = 0; i < 8; i++) {
			double[] p = project(cam, (i & 1) == 0 ? b.minX : b.maxX, (i & 2) == 0 ? b.minY : b.maxY,
					(i & 4) == 0 ? b.minZ : b.maxZ, mc.options.fov().get(), sw, sh);
			if (p == null) return; // цель частично позади камеры - не рисуем
			x0 = Math.min(x0, p[0]); y0 = Math.min(y0, p[1]);
			x1 = Math.max(x1, p[0]); y1 = Math.max(y1, p[1]);
		}
		int ix0 = (int) x0 - 2, iy0 = (int) y0 - 2, ix1 = (int) x1 + 2, iy1 = (int) y1 + 2;
		if (ix1 - ix0 > sw * 2 || iy1 - iy0 > sh * 2) return;
		int col = 0xFF000000 | Cfg.ESP_COLOR.rgb();

		if (Cfg.ESP_STYLE.idx == 0) {
			box(g, ix0, iy0, ix1, iy1, col);
		} else {
			int len = Math.max(4, Math.min(ix1 - ix0, iy1 - iy0) / 4);
			corner(g, ix0, iy0, len, 1, 1, col);
			corner(g, ix1, iy0, len, -1, 1, col);
			corner(g, ix0, iy1, len, 1, -1, col);
			corner(g, ix1, iy1, len, -1, -1, col);
		}
		if (Cfg.ESP_HP.v) {
			float hp = Math.max(0f, Math.min(1f, t.getHealth() / Math.max(1f, t.getMaxHealth())));
			int bx = ix0 - 5, h = iy1 - iy0, fill = (int) (h * hp);
			g.fill(bx - 1, iy0 - 1, bx + 2, iy1 + 1, 0xAA000000);
			int hc = Cfg.hsv(hp * 0.33f, 0.85f, 1f) | 0xFF000000;
			g.fill(bx, iy1 - fill, bx + 1, iy1, hc);
		}
		if (Cfg.ESP_NAME.v) {
			String n = t.getName().getString();
			g.drawString(mc.font, n, (ix0 + ix1) / 2 - mc.font.width(n) / 2, iy0 - 11, col);
		}
	}

	private static void box(GuiGraphics g, int x0, int y0, int x1, int y1, int c) {
		g.fill(x0, y0, x1, y0 + 1, c);
		g.fill(x0, y1 - 1, x1, y1, c);
		g.fill(x0, y0, x0 + 1, y1, c);
		g.fill(x1 - 1, y0, x1, y1, c);
	}

	private static void corner(GuiGraphics g, int x, int y, int len, int dx, int dy, int c) {
		g.fill(Math.min(x, x + dx * len), y, Math.max(x, x + dx * len), y + dy, c);
		g.fill(x, Math.min(y, y + dy * len), x + dx, Math.max(y, y + dy * len), c);
	}

	/** Экранные координаты точки мира (в GUI-пикселях) или null, если точка позади камеры. */
	private static double[] project(Camera cam, double wx, double wy, double wz, double fovDeg, int sw, int sh) {
		double dx = wx - cam.position().x, dy = wy - cam.position().y, dz = wz - cam.position().z;
		double yaw = Math.toRadians(cam.yRot()), pitch = Math.toRadians(cam.xRot());
		double cp = Math.cos(pitch);
		double fx = -Math.sin(yaw) * cp, fy = -Math.sin(pitch), fz = Math.cos(yaw) * cp;
		double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
		double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;
		double depth = dx * fx + dy * fy + dz * fz;
		if (depth < 0.05) return null;
		double th = Math.tan(Math.toRadians(fovDeg) / 2.0);
		double aspect = (double) sw / sh;
		double nx = (dx * rx + dz * rz) / (depth * th * aspect);
		double ny = (dx * ux + dy * uy + dz * uz) / (depth * th);
		return new double[] {(nx * 0.5 + 0.5) * sw, (0.5 - ny * 0.5) * sh};
	}
}
