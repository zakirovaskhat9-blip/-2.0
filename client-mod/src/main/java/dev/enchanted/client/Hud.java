package dev.enchanted.client;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

/** Target ESP: стильная 2D-метка на цели боя в духе Nursultan (вращающийся ромб, свечение, градиент темы). Сквозь стены не светит. */
public final class Hud {
	private static LivingEntity target;
	private static long until;

	static void hit(Entity e) {
		if (e instanceof LivingEntity le) {
			target = le;
			until = System.currentTimeMillis() + (long) (Cfg.ESP_TIME.v * 1000);
		}
	}

	/** Цель последнего удара, пока она жива и не истёк срок. */
	static LivingEntity recent() {
		LivingEntity t = target;
		return t != null && System.currentTimeMillis() <= until && t.isAlive() && !t.isRemoved() ? t : null;
	}

	public static void render(GuiGraphics g, DeltaTracker dt) {
		Minecraft mc = Minecraft.getInstance();
		if (!Cfg.TARGET_ESP.enabled || mc.player == null || mc.level == null || mc.options.hideGui) return;
		Gfx.alphaMul = 1f;
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
			x0 = Math.min(x0, p[0]);
			y0 = Math.min(y0, p[1]);
			x1 = Math.max(x1, p[0]);
			y1 = Math.max(y1, p[1]);
		}
		int ix0 = (int) x0 - 2, iy0 = (int) y0 - 2, ix1 = (int) x1 + 2, iy1 = (int) y1 + 2;
		if (ix1 - ix0 > sw * 2 || iy1 - iy0 > sh * 2) return;
		double cx = (ix0 + ix1) / 2.0, cy = (iy0 + iy1) / 2.0;

		// цвета: градиент темы или свой цвет; при ударе (hurtTime) вспыхивает красным
		int c0 = Cfg.ESP_THEME.v ? Cfg.accent() : 0xFF000000 | Cfg.ESP_COLOR.rgb();
		int c1 = Cfg.ESP_THEME.v ? Cfg.accent2() : c0;
		float flash = Math.min(1f, t.hurtTime / 8f);
		c0 = Gfx.mix(c0, 0xFFFF3B3B, flash);
		c1 = Gfx.mix(c1, 0xFFFF6A3B, flash);
		float pulse = Cfg.ESP_ANIM.v ? 0.78f + 0.22f * (float) Math.sin(now / 190.0) : 1f;
		int len = Math.max(4, Math.min(ix1 - ix0, iy1 - iy0) / 4);

		switch (Cfg.ESP_STYLE.idx) {
			case 0 -> marker(g, cx, cy, Math.max(10, Math.min(44, (iy1 - iy0) * 0.3)), now, c0, c1, pulse);
			case 1 -> {
				Gfx.glow(g, ix0, iy0, ix1 - ix0, iy1 - iy0, 2, c0 & 0xFFFFFF, 0.35f * pulse, 3);
				corner(g, ix0, iy0, len, 1, 1, Gfx.a(c0, pulse));
				corner(g, ix1, iy0, len, -1, 1, Gfx.a(c1, pulse));
				corner(g, ix0, iy1, len, 1, -1, Gfx.a(c1, pulse));
				corner(g, ix1, iy1, len, -1, -1, Gfx.a(c0, pulse));
			}
			case 2 -> ring(g, cx, cy, (ix1 - ix0) / 2.0 + 4, (iy1 - iy0) / 2.0 + 4, now, c0, c1, pulse);
			case 3 -> {
				Gfx.glow(g, ix0, iy0, ix1 - ix0, iy1 - iy0, 3, c0 & 0xFFFFFF, 0.9f * pulse, 6);
				box(g, ix0, iy0, ix1, iy1, Gfx.a(c0, pulse));
			}
			default -> box(g, ix0, iy0, ix1, iy1, Gfx.a(c0, pulse));
		}

		int bw = Math.max(44, Math.min(96, ix1 - ix0));
		int mid = (int) cx;
		if (Cfg.ESP_HP.v) {
			float hp = Math.max(0f, Math.min(1f, t.getHealth() / Math.max(1f, t.getMaxHealth())));
			float shown = Gfx.anim("esp.hp").to(hp, 12f);
			if (t != lastT) {
				lastT = t;
				Gfx.anim("esp.hp").at(hp);
				shown = hp;
			}
			int bx = mid - bw / 2, by = iy1 + 5;
			Gfx.rr(g, bx - 1, by - 1, bw + 2, 7, 3, 0xB0000000);
			Gfx.rr(g, bx, by, bw, 5, 2, 0xFF1B1B22);
			int fw = Math.max(3, (int) (bw * shown));
			Gfx.rr(g, bx, by, fw, 5, 2, 0xFF000000 | Cfg.hsv(shown * 0.33f, 0.8f, 1f));
		}
		if (Cfg.ESP_NAME.v || Cfg.ESP_DIST.v) {
			String n = Cfg.ESP_NAME.v ? t.getName().getString() : "";
			String d = Cfg.ESP_DIST.v ? String.format("%.1f м", mc.player.distanceTo(t)) : "";
			String all = n.isEmpty() ? d : d.isEmpty() ? n : n + "  " + d;
			int tw = mc.font.width(all);
			int px = mid - tw / 2 - 5, py = iy0 - 17;
			Gfx.panel(g, px, py, tw + 10, 13);
			if (!n.isEmpty()) g.drawString(mc.font, n, px + 5, py + 3, 0xFFFFFFFF);
			if (!d.isEmpty()) g.drawString(mc.font, d, px + 5 + (n.isEmpty() ? 0 : mc.font.width(n + "  ")), py + 3, 0xFFA0A0A8);
		}
	}

	private static LivingEntity lastT;

	/** Вращающийся ромб со свечением и градиентом - главный стиль. */
	private static void marker(GuiGraphics g, double cx, double cy, double r, long now, int c0, int c1, float pulse) {
		double a = Cfg.ESP_ANIM.v ? now / 700.0 : Math.PI / 4;
		for (int k = 3; k >= 1; k--) { // мягкое свечение: увеличенные бледные копии
			quad(g, cx, cy, r * (1 + 0.11 * k), a, Gfx.a(c0, 0.16f * pulse / k), Gfx.a(c1, 0.16f * pulse / k), false);
		}
		quad(g, cx, cy, r, a, Gfx.a(c0, 0.20f * pulse), Gfx.a(c1, 0.20f * pulse), true);   // полупрозрачная заливка
		quad(g, cx, cy, r, a, Gfx.a(c0, pulse), Gfx.a(c1, pulse), false);                 // контур
		quad(g, cx, cy, r - 1, a, Gfx.a(c0, 0.55f * pulse), Gfx.a(c1, 0.55f * pulse), false);
		g.fill((int) cx - 1, (int) cy - 1, (int) cx + 2, (int) cy + 2, Gfx.a(c1, pulse)); // точка в центре
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

	/** Эллипс с бегущей яркой дугой. */
	private static void ring(GuiGraphics g, double cx, double cy, double rx, double ry, long now, int c0, int c1, float pulse) {
		int n = 120;
		double head = Cfg.ESP_ANIM.v ? (now / 900.0) % 1.0 : 0;
		for (int i = 0; i < n; i++) {
			double f = i / (double) n, a = f * Math.PI * 2;
			double d = ((f - head) % 1.0 + 1.0) % 1.0; // расстояние за «головой» дуги
			float k = (float) Math.max(0.0, 1.0 - d * 2.2);
			int c = Gfx.mix(Gfx.a(c0, 0.35f), Gfx.mix(c0, c1, f > 0.5 ? 2f - 2f * (float) f : 2f * (float) f), k);
			int x = (int) Math.round(cx + Math.cos(a) * rx), y = (int) Math.round(cy + Math.sin(a) * ry);
			g.fill(x, y, x + 1 + (k > 0.5f ? 1 : 0), y + 1 + (k > 0.5f ? 1 : 0), Gfx.a(c, pulse));
		}
	}

	private static void line(GuiGraphics g, double x0, double y0, double x1, double y1, int c0, int c1) {
		int n = (int) Math.max(1, Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)));
		for (int i = 0; i <= n; i++) {
			int x = (int) Math.round(x0 + (x1 - x0) * i / n), y = (int) Math.round(y0 + (y1 - y0) * i / n);
			g.fill(x, y, x + 1, y + 1, Gfx.mix(c0, c1, i / (float) n));
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
