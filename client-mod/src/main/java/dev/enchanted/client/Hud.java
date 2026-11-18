package dev.enchanted.client;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

/** Target ESP: вращающийся ромб со свечением и градиентом темы на цели боя (как в Nursultan). Сквозь стены не светит. */
public final class Hud {
	private static final long HOLD = 4000L, FADE_IN = 320L, FADE_OUT = 350L;
	private static LivingEntity target;
	private static long since, until;

	static void hit(Entity e) {
		if (!(e instanceof LivingEntity le)) return;
		long now = System.currentTimeMillis();
		if (le != target || now > until) since = now; // новая цель - ромб «выпрыгивает» заново
		target = le;
		until = now + HOLD;
	}

	public static void render(GuiGraphics g, DeltaTracker dt) {
		Minecraft mc = Minecraft.getInstance();
		if (!Cfg.TARGET_ESP.enabled || mc.player == null || mc.level == null || mc.options.hideGui) return;
		Gfx.alphaMul = 1f;
		long now = System.currentTimeMillis();
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
			x0 = Math.min(x0, p[0]);
			y0 = Math.min(y0, p[1]);
			x1 = Math.max(x1, p[0]);
			y1 = Math.max(y1, p[1]);
		}
		if (x1 - x0 > sw * 2 || y1 - y0 > sh * 2) return;
		double cx = (x0 + x1) / 2.0, cy = (y0 + y1) / 2.0;

		// появление (с «отскоком») и затухание перед концом; при ударе по цели - красная вспышка
		float in = Gfx.easeOutBack((now - since) / (float) FADE_IN);
		float alpha = Gfx.clamp01((now - since) / 200f) * Gfx.clamp01((until - now) / (float) FADE_OUT);
		float flash = Math.min(1f, t.hurtTime / 8f);
		int c0 = Gfx.mix(Cfg.accent(), 0xFFFF3B3B, flash), c1 = Gfx.mix(Cfg.accent2(), 0xFFFF6A3B, flash);
		float pulse = (0.8f + 0.2f * (float) Math.sin(now / 190.0)) * alpha;
		double r = Math.max(10, Math.min(44, (y1 - y0) * 0.3)) * in;
		marker(g, cx, cy, r, now, c0, c1, pulse);
	}

	/** Вращающийся ромб со свечением и градиентом. */
	private static void marker(GuiGraphics g, double cx, double cy, double r, long now, int c0, int c1, float pulse) {
		double a = now / 700.0;
		for (int k = 3; k >= 1; k--) { // мягкое свечение: увеличенные бледные копии
			quad(g, cx, cy, r * (1 + 0.11 * k), a, Gfx.a(c0, 0.16f * pulse / k), Gfx.a(c1, 0.16f * pulse / k), false);
		}
		quad(g, cx, cy, r, a, Gfx.a(c0, 0.20f * pulse), Gfx.a(c1, 0.20f * pulse), true);
		quad(g, cx, cy, r, a, Gfx.a(c0, pulse), Gfx.a(c1, pulse), false);
		quad(g, cx, cy, r - 1, a, Gfx.a(c0, 0.55f * pulse), Gfx.a(c1, 0.55f * pulse), false);
		g.fill((int) cx - 1, (int) cy - 1, (int) cx + 2, (int) cy + 2, Gfx.a(c1, pulse));
	}

	/** Квадрат, повёрнутый на угол a: контур (градиент между цветами) или заливка. */
	private static void quad(GuiGraphics g, double cx, double cy, double r, double a, int c0, int c1, boolean fill) {
		double[] px = new double[4], py = new double[4];
		for (int k = 0; k < 4; k++) {
			px[k] = cx + Math.cos(a + k * Math.PI / 2) * r;
			py[k] = cy + Math.sin(a + k * Math.PI / 2) * r;
		}
		if (fill) {
			int minY = (int) Math.floor(Math.min(Math.min(py[0], py[1]), Math.min(py[2], py[3])));
			int maxY = (int) Math.ceil(Math.max(Math.max(py[0], py[1]), Math.max(py[2], py[3])));
			for (int y = minY; y <= maxY; y++) {
				double lo = 1e9, hi = -1e9;
				for (int k = 0; k < 4; k++) {
					int n = (k + 1) % 4;
					double ya = py[k], yb = py[n];
					if ((y + 0.5 < Math.min(ya, yb)) || (y + 0.5 >= Math.max(ya, yb)) || ya == yb) continue;
					double x = px[k] + (px[n] - px[k]) * (y + 0.5 - ya) / (yb - ya);
					lo = Math.min(lo, x);
					hi = Math.max(hi, x);
				}
				if (hi > lo) g.fill((int) Math.round(lo), y, (int) Math.round(hi), y + 1, Gfx.mix(c0, c1, (float) ((y - minY) / (double) Math.max(1, maxY - minY))));
			}
			return;
		}
		for (int k = 0; k < 4; k++) {
			int n = (k + 1) % 4;
			line(g, px[k], py[k], px[n], py[n], Gfx.mix(c0, c1, k / 3f), Gfx.mix(c0, c1, Math.min(1f, (k + 1) / 3f)));
		}
	}

	private static void line(GuiGraphics g, double x0, double y0, double x1, double y1, int c0, int c1) {
		int n = (int) Math.max(1, Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)));
		for (int i = 0; i <= n; i++) {
			int x = (int) Math.round(x0 + (x1 - x0) * i / n), y = (int) Math.round(y0 + (y1 - y0) * i / n);
			g.fill(x, y, x + 1, y + 1, Gfx.mix(c0, c1, i / (float) n));
		}
	}

	/** Экранные координаты точки мира (в GUI-пикселях) или null, если точка позади камеры. */
	static double[] project(Camera cam, double wx, double wy, double wz, double fovDeg, int sw, int sh) {
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
