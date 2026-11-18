package dev.enchanted.client;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

/**
 * Target ESP: вращающийся ромб на цели боя (как в Nursultan). Два вида: «Ромб» (замкнутый контур) и «Скобки» (уголки).
 * Рисуется поворотом матрицы GUI и несколькими fill-прямоугольниками (около 20 вызовов за кадр вместо сотен пикселей).
 * Сквозь стены не светит. Проекция считается без аллокаций.
 */
public final class Hud {
	private static final long HOLD = 4000L, FADE_IN = 320L, FADE_OUT = 350L;
	private static final double SQ = 0.70710678;
	private static LivingEntity target;
	private static long since, until;

	// базис камеры (считается один раз за кадр) и результат проекции
	private static double camX, camY, camZ, fx, fy, fz, rx, rz, ux, uy, uz, tanH, aspect, sx, sy;
	private static int scrW, scrH;

	static void hit(Entity e) {
		if (!(e instanceof LivingEntity le)) return;
		long now = System.currentTimeMillis();
		if (le != target || now > until) since = now; // новая цель - ромб «выпрыгивает» заново
		target = le;
		until = now + HOLD;
	}

	public static void render(GuiGraphics g, DeltaTracker dt) {
		LivingEntity t = target;
		if (t == null) return; // дёшево выходим, пока цели нет
		Minecraft mc = Minecraft.getInstance();
		if (!Cfg.TARGET_ESP.enabled || mc.player == null || mc.level == null || mc.options.hideGui) return;
		long now = System.currentTimeMillis();
		if (now > until || !t.isAlive() || t.isRemoved() || t == mc.player) {
			if (now > until) target = null;
			return;
		}
		Gfx.alphaMul = 1f;

		Camera cam = mc.gameRenderer.getMainCamera();
		if (cam == null || !cam.isInitialized()) return;
		float pt = dt.getGameTimeDeltaPartialTick(false);
		scrW = mc.getWindow().getGuiScaledWidth();
		scrH = mc.getWindow().getGuiScaledHeight();
		prepare(cam, mc.options.fov().get());

		double ox = (t.xo - t.getX()) * (1.0 - pt), oy = (t.yo - t.getY()) * (1.0 - pt), oz = (t.zo - t.getZ()) * (1.0 - pt);
		AABB b = t.getBoundingBox().move(ox, oy, oz);

		double x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
		for (int i = 0; i < 8; i++) {
			if (!project((i & 1) == 0 ? b.minX : b.maxX, (i & 2) == 0 ? b.minY : b.maxY, (i & 4) == 0 ? b.minZ : b.maxZ)) return; // цель частично позади камеры
			if (sx < x0) x0 = sx;
			if (sx > x1) x1 = sx;
			if (sy < y0) y0 = sy;
			if (sy > y1) y1 = sy;
		}
		if (x1 - x0 > scrW * 2 || y1 - y0 > scrH * 2) return;
		double cx = (x0 + x1) / 2.0, cy = (y0 + y1) / 2.0;
		if (cx < -60 || cy < -60 || cx > scrW + 60 || cy > scrH + 60) return; // вне экрана - не рисуем

		// появление (с «отскоком») и затухание перед концом; при ударе по цели - красная вспышка
		float in = Gfx.easeOutBack((now - since) / (float) FADE_IN);
		float alpha = Gfx.clamp01((now - since) / 200f) * Gfx.clamp01((until - now) / (float) FADE_OUT);
		if (alpha <= 0.01f) return;
		float flash = Math.min(1f, t.hurtTime / 8f);
		int c0 = Gfx.mix(Cfg.accent(), 0xFFFF3B3B, flash), c1 = Gfx.mix(Cfg.accent2(), 0xFFFF6A3B, flash);
		float pulse = (0.8f + 0.2f * (float) Math.sin(now / 190.0)) * alpha;
		double r = Math.max(10, Math.min(44, (y1 - y0) * 0.3)) * in;
		if (r < 2) return;

		var pose = g.pose();
		pose.pushMatrix();
		pose.translate((float) cx, (float) cy);
		if (Cfg.ESP_STYLE.idx == 1) {
			pose.rotate((float) (now / 1100.0));
			brackets(g, (int) Math.round(r * SQ), c0, c1, pulse);
		} else {
			pose.rotate((float) (now / 700.0));
			diamond(g, (int) Math.round(r * SQ), c0, c1, pulse);
		}
		pose.popMatrix();
	}

	// ---------------- виды ----------------

	/** Вид 1: замкнутый контур + мягкое свечение + лёгкая заливка + точка в центре. */
	private static void diamond(GuiGraphics g, int h, int c0, int c1, float pulse) {
		for (int k = 3; k >= 1; k--) { // свечение: увеличенные бледные контуры
			frame(g, h + k * 2, 1, Gfx.a(c0, 0.16f * pulse / k), Gfx.a(c1, 0.16f * pulse / k));
		}
		g.fillGradient(-h, -h, h, h, Gfx.a(c0, 0.20f * pulse), Gfx.a(c1, 0.20f * pulse));
		frame(g, h, 1, Gfx.a(c0, pulse), Gfx.a(c1, pulse));
		frame(g, h - 1, 1, Gfx.a(c0, 0.55f * pulse), Gfx.a(c1, 0.55f * pulse));
		g.fill(-1, -1, 2, 2, Gfx.a(c1, pulse));
	}

	/** Контур квадрата со стороной 2h: верх и низ - цвета c0 и c1, бока - вертикальный градиент. */
	private static void frame(GuiGraphics g, int h, int t, int c0, int c1) {
		g.fill(-h, -h, h, -h + t, c0);
		g.fill(-h, h - t, h, h, c1);
		g.fillGradient(-h, -h + t, -h + t, h - t, c0, c1);
		g.fillGradient(h - t, -h + t, h, h - t, c0, c1);
	}

	/** Вид 2: четыре уголка (как в Nursultan) со свечением; цвет плавно идёт от c0 к c1 по кругу. */
	private static void brackets(GuiGraphics g, int h, int c0, int c1, float pulse) {
		int len = Math.max(4, Math.round(h * 0.55f));
		for (int layer = 2; layer >= 0; layer--) {
			int th = 2 + layer * 2; // слой 0 - сама линия, 1 и 2 - свечение
			float al = layer == 0 ? 1f : layer == 1 ? 0.22f : 0.10f;
			for (int k = 0; k < 4; k++) {
				int sxk = (k & 1) == 0 ? -1 : 1, syk = (k & 2) == 0 ? -1 : 1;
				int col = Gfx.a(Gfx.mix(c0, c1, Gfx.tri(k / 4f + 0.25f)), al * pulse);
				int cx0 = sxk * h, cy0 = syk * h, e = (th - 2) / 2;
				// горизонтальная и вертикальная «ножки» уголка
				arm(g, cx0 + sxk * e, cy0 + syk * e, cx0 - sxk * (len + e), cy0 - syk * (th - e), col);
				arm(g, cx0 + sxk * e, cy0 + syk * e, cx0 - sxk * (th - e), cy0 - syk * (len + e), col);
			}
		}
	}

	private static void arm(GuiGraphics g, int xa, int ya, int xb, int yb, int c) {
		g.fill(Math.min(xa, xb), Math.min(ya, yb), Math.max(xa, xb), Math.max(ya, yb), c);
	}

	// ---------------- проекция без аллокаций ----------------

	private static void prepare(Camera cam, double fovDeg) {
		camX = cam.position().x;
		camY = cam.position().y;
		camZ = cam.position().z;
		double yaw = Math.toRadians(cam.yRot()), pitch = Math.toRadians(cam.xRot());
		double cp = Math.cos(pitch), sy0 = Math.sin(yaw), cy0 = Math.cos(yaw);
		fx = -sy0 * cp;
		fy = -Math.sin(pitch);
		fz = cy0 * cp;
		rx = -cy0;
		rz = -sy0;
		ux = -rz * fy;
		uy = rz * fx - rx * fz;
		uz = rx * fy;
		tanH = Math.tan(Math.toRadians(fovDeg) / 2.0);
		aspect = (double) scrW / scrH;
	}

	/** Экранные координаты точки мира (GUI-пиксели) в sx/sy; false, если точка позади камеры. */
	private static boolean project(double wx, double wy, double wz) {
		double dx = wx - camX, dy = wy - camY, dz = wz - camZ;
		double depth = dx * fx + dy * fy + dz * fz;
		if (depth < 0.05) return false;
		double nx = (dx * rx + dz * rz) / (depth * tanH * aspect);
		double ny = (dx * ux + dy * uy + dz * uz) / (depth * tanH);
		sx = (nx * 0.5 + 0.5) * scrW;
		sy = (0.5 - ny * 0.5) * scrH;
		return true;
	}
}
