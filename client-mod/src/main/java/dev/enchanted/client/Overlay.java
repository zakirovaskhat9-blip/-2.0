package dev.enchanted.client;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** HUD-модули: Watermark, Info HUD, Keystrokes, ArrayList. Рисуются поверх игры, на мир не влияют. */
public final class Overlay {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
	private static final String[] DIRS = {"Ю", "ЮЗ", "З", "СЗ", "С", "СВ", "В", "ЮВ"};
	private static final int BG = 0xB0101010, GRAY = 0xFF9A9A9A, WHITE = 0xFFFFFFFF, DARK = 0xFF101010;

	private static final ArrayDeque<Long> LMB = new ArrayDeque<>(), RMB = new ArrayDeque<>();
	private static boolean wasL, wasR, hasLast;
	private static double lastX, lastZ, speed;
	private static int fps, frames;
	private static long fpsT = System.currentTimeMillis();

	/** Раз в тик: скорость игрока и клики для CPS. */
	static void tick(Minecraft mc) {
		if (mc.player == null) {
			hasLast = false;
			return;
		}
		double x = mc.player.getX(), z = mc.player.getZ();
		if (hasLast) speed = speed * 0.7 + Math.hypot(x - lastX, z - lastZ) * 20.0 * 0.3;
		lastX = x;
		lastZ = z;
		hasLast = true;
		long now = System.currentTimeMillis();
		boolean l = mc.options.keyAttack.isDown(), r = mc.options.keyUse.isDown();
		if (l && !wasL) LMB.add(now);
		if (r && !wasR) RMB.add(now);
		wasL = l;
		wasR = r;
	}

	private static long hitT;
	private static LivingEntity shownFor;
	private static float shownHp;

	/** Вызывается при ударе по сущности. */
	static void hitMark() {
		hitT = System.currentTimeMillis();
	}

	private static void line(GuiGraphics g, int x0, int y0, int x1, int y1, int c) {
		int n = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
		for (int i = 0; i <= n; i++) {
			int x = x0 + (x1 - x0) * i / Math.max(1, n), y = y0 + (y1 - y0) * i / Math.max(1, n);
			g.fill(x, y, x + 1, y + 1, c);
		}
	}

	private static void hitMarker(GuiGraphics g, int sw, int sh, long now) {
		long age = now - hitT;
		if (age > 350) return;
		float a = 1f - age / 350f;
		int c = ((int) (255 * a) << 24) | Cfg.HM_COLOR.rgb();
		int cx = sw / 2, cy = sh / 2, gap = 3, len = (int) Cfg.HM_SIZE.v;
		line(g, cx - gap, cy - gap, cx - gap - len, cy - gap - len, c);
		line(g, cx + gap, cy - gap, cx + gap + len, cy - gap - len, c);
		line(g, cx - gap, cy + gap, cx - gap - len, cy + gap + len, c);
		line(g, cx + gap, cy + gap, cx + gap + len, cy + gap + len, c);
	}

	private static void targetHud(GuiGraphics g, Minecraft mc, int sw, int sh) {
		LivingEntity t = Hud.recent();
		if (t == null && mc.crosshairPickEntity instanceof LivingEntity le && le != mc.player) t = le;
		if (t == null) return;
		if (t != shownFor) {
			shownFor = t;
			shownHp = t.getHealth();
		}
		float hp = t.getHealth(), max = Math.max(1f, t.getMaxHealth());
		shownHp += (hp - shownHp) * 0.15f;
		String n = t.getName().getString();
		String hs = String.format("%.1f / %.0f", hp, max);
		int w = Math.max(120, Math.max(mc.font.width(n), mc.font.width(hs)) + 20), h = 38;
		int x = sw / 2 + 28, y = sh / 2 + 18;
		float f = Math.max(0f, Math.min(1f, shownHp / max));
		int hc = 0xFF000000 | Cfg.hsv(f * 0.33f, 0.8f, 1f);
		rr(g, x, y, w, h, 5, BG);
		g.drawString(mc.font, n, x + 8, y + 6, WHITE);
		g.drawString(mc.font, hs, x + 8, y + 17, GRAY);
		rr(g, x + 8, y + 29, w - 16, 4, 2, 0xFF2A2A2A);
		rr(g, x + 8, y + 29, Math.max(3, (int) ((w - 16) * f)), 4, 2, hc);
	}

	private static void armor(GuiGraphics g, Minecraft mc, int sw, int sh) {
		EquipmentSlot[] sl = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
		int x = sw - 26, y = sh / 2 - 46;
		for (int i = 0; i < sl.length; i++) {
			ItemStack s = mc.player.getItemBySlot(sl[i]);
			int yy = y + i * 24;
			if (s.isEmpty()) continue;
			rr(g, x - 3, yy - 3, 22, 22, 4, BG);
			g.renderItem(s, x, yy);
			if (s.isDamageableItem() && s.getMaxDamage() > 0) {
				float f = Math.max(0f, 1f - (float) s.getDamageValue() / s.getMaxDamage());
				g.fill(x, yy + 15, x + 16, yy + 17, 0xFF202020);
				g.fill(x, yy + 15, x + Math.max(1, (int) (16 * f)), yy + 17, 0xFF000000 | Cfg.hsv(f * 0.33f, 0.85f, 1f));
			}
		}
	}

	private static void effects(GuiGraphics g, Minecraft mc, int sh, int ac) {
		int i = 0;
		for (MobEffectInstance e : new ArrayList<>(mc.player.getActiveEffects())) {
			String nm = e.getEffect().value().getDisplayName().getString() + (e.getAmplifier() > 0 ? " " + (e.getAmplifier() + 1) : "");
			int sec = e.getDuration() / 20;
			String tm = e.getDuration() < 0 ? "--" : (sec / 60) + ":" + String.format("%02d", sec % 60);
			int w = mc.font.width(nm + "  " + tm) + 14, y = sh - 50 - i * 15;
			rr(g, 6, y, w, 13, 3, BG);
			g.fill(6, y + 2, 8, y + 11, ac);
			g.drawString(mc.font, nm, 12, y + 3, WHITE);
			g.drawString(mc.font, tm, 12 + mc.font.width(nm + "  "), y + 3, GRAY);
			i++;
		}
	}

	private static int cps(ArrayDeque<Long> q) {
		long now = System.currentTimeMillis();
		while (!q.isEmpty() && now - q.peekFirst() > 1000) q.pollFirst();
		return q.size();
	}

	private static void rr(GuiGraphics g, int x, int y, int w, int h, int r, int c) {
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		for (int i = 0; i < r; i++) {
			double d = r - i - 0.5;
			int inset = r - (int) Math.round(Math.sqrt(Math.max(0, r * r - d * d)));
			g.fill(x + inset, y + i, x + w - inset, y + i + 1, c);
			g.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, c);
		}
		g.fill(x, y + r, x + w, y + h - r, c);
	}

	public static void render(GuiGraphics g, DeltaTracker dt) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || mc.options.hideGui) return;

		frames++;
		long now = System.currentTimeMillis();
		if (now - fpsT >= 500) {
			fps = (int) Math.round(frames * 1000.0 / (now - fpsT));
			frames = 0;
			fpsT = now;
		}

		int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
		int ac = 0xFF000000 | Cfg.OV_COLOR.rgb();
		int infoY = 30;
		if (Cfg.WM.enabled) infoY = watermark(g, mc, ac);
		if (Cfg.INFO.enabled) info(g, mc, 6, infoY + 6, ac);
		if (Cfg.KEYS.enabled) keys(g, mc, 6, sh / 2 + 8, ac);
		if (Cfg.ARRAY.enabled) array(g, mc, sw, now);
		if (Cfg.THUD.enabled) targetHud(g, mc, sw, sh);
		if (Cfg.ARMOR.enabled) armor(g, mc, sw, sh);
		if (Cfg.EFFECTS.enabled) effects(g, mc, sh, ac);
		if (Cfg.HITMARK.enabled) hitMarker(g, sw, sh, now);
	}

	private static int watermark(GuiGraphics g, Minecraft mc, int ac) {
		String head = "Enchanted";
		StringBuilder tail = new StringBuilder();
		if (Cfg.WM_FPS.v) tail.append("  |  ").append(fps).append(" FPS");
		if (Cfg.WM_TIME.v) tail.append("  |  ").append(LocalTime.now().format(TIME));
		int hw = mc.font.width(head);
		int w = hw + mc.font.width(tail.toString()) + 16;
		rr(g, 6, 6, w, 17, 4, BG);
		g.drawString(mc.font, head, 14, 11, ac);
		g.drawString(mc.font, tail.toString(), 14 + hw, 11, GRAY);
		long t = System.currentTimeMillis();
		for (int i = 0; i < w - 12; i += 2) {
			int c = Cfg.OV_COLOR.idx == Cfg.RAINBOW
					? 0xFF000000 | Cfg.hsv((t % 3000L) / 3000f + i / (float) w * 0.6f, 0.65f, 1f) : ac;
			g.fill(12 + i, 20, 14 + i, 21, c);
		}
		return 23;
	}

	private static void info(GuiGraphics g, Minecraft mc, int x, int y, int ac) {
		List<String[]> rows = new ArrayList<>();
		if (Cfg.I_FPS.v) rows.add(new String[] {"FPS", String.valueOf(fps)});
		if (Cfg.I_XYZ.v) {
			rows.add(new String[] {"XYZ", (int) Math.floor(mc.player.getX()) + " " + (int) Math.floor(mc.player.getY()) + " " + (int) Math.floor(mc.player.getZ())});
		}
		if (Cfg.I_SPEED.v) rows.add(new String[] {"Скорость", String.format("%.1f б/с", speed)});
		if (Cfg.I_DIR.v) {
			float yaw = ((mc.player.getYRot() % 360f) + 360f) % 360f;
			rows.add(new String[] {"Курс", DIRS[((int) ((yaw + 22.5f) / 45f)) % 8] + " " + (int) yaw + "\u00b0"});
		}
		if (rows.isEmpty()) return;
		int w = 0;
		for (String[] r : rows) w = Math.max(w, mc.font.width(r[0] + "  " + r[1]));
		w += 16;
		int h = rows.size() * 11 + 7;
		rr(g, x, y, w, h, 4, BG);
		g.fill(x, y + 4, x + 2, y + h - 4, ac);
		int ry = y + 5;
		for (String[] r : rows) {
			g.drawString(mc.font, r[0], x + 8, ry, GRAY);
			g.drawString(mc.font, r[1], x + 8 + mc.font.width(r[0] + "  "), ry, WHITE);
			ry += 11;
		}
	}

	private static void key(GuiGraphics g, Minecraft mc, int x, int y, int w, int h, String s, boolean down, int ac, String sub) {
		rr(g, x, y, w, h, 4, down ? (ac & 0xFFFFFF) | 0xCC000000 : BG);
		int tc = down ? DARK : WHITE;
		if (sub == null) {
			g.drawString(mc.font, s, x + (w - mc.font.width(s)) / 2, y + (h - 8) / 2, tc);
		} else {
			g.drawString(mc.font, s, x + (w - mc.font.width(s)) / 2, y + 3, tc);
			g.drawString(mc.font, sub, x + (w - mc.font.width(sub)) / 2, y + 12, down ? DARK : GRAY);
		}
	}

	private static void keys(GuiGraphics g, Minecraft mc, int x, int y, int ac) {
		int k = 20, gp = 2, step = k + gp;
		key(g, mc, x + step, y, k, k, "W", mc.options.keyUp.isDown(), ac, null);
		key(g, mc, x, y + step, k, k, "A", mc.options.keyLeft.isDown(), ac, null);
		key(g, mc, x + step, y + step, k, k, "S", mc.options.keyDown.isDown(), ac, null);
		key(g, mc, x + 2 * step, y + step, k, k, "D", mc.options.keyRight.isDown(), ac, null);
		int full = 3 * k + 2 * gp;
		key(g, mc, x, y + 2 * step, full, 12, "-----", mc.options.keyJump.isDown(), ac, null);
		int mw = (full - gp) / 2, my = y + 2 * step + 12 + gp;
		boolean c = Cfg.K_CPS.v;
		key(g, mc, x, my, mw, k + 2, "ЛКМ", mc.options.keyAttack.isDown(), ac, c ? String.valueOf(cps(LMB)) : null);
		key(g, mc, x + mw + gp, my, mw, k + 2, "ПКМ", mc.options.keyUse.isDown(), ac, c ? String.valueOf(cps(RMB)) : null);
	}

	private static void array(GuiGraphics g, Minecraft mc, int sw, long now) {
		List<Cfg.Module> on = new ArrayList<>();
		for (Cfg.Module m : Cfg.ALL) if (m.enabled) on.add(m);
		on.sort((a, b) -> mc.font.width(b.name) - mc.font.width(a.name));
		int y = 6, i = 0;
		for (Cfg.Module m : on) {
			int w = mc.font.width(m.name) + 12, x = sw - 6 - w;
			int c = 0xFF000000 | (Cfg.OV_COLOR.idx == Cfg.RAINBOW
					? Cfg.hsv((now % 4000L) / 4000f + i * 0.07f, 0.65f, 1f) : Cfg.OV_COLOR.rgb());
			rr(g, x, y, w, 13, 3, BG);
			g.fill(x + w - 2, y + 2, x + w, y + 11, c);
			g.drawString(mc.font, m.name, x + 5, y + 3, WHITE);
			y += 14;
			i++;
		}
	}
}
