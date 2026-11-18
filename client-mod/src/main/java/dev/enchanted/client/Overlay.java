package dev.enchanted.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** HUD: счётчик FPS, здоровье противника под прицелом и статус брони. */
public final class Overlay {
	private static final int GRAY = 0xFF9A9A9A, WHITE = 0xFFFFFFFF;

	private static int fps, frames;
	private static long fpsT = System.currentTimeMillis();

	public static void render(GuiGraphics g, DeltaTracker dt) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || mc.options.hideGui) return;
		Gfx.alphaMul = 1f;

		frames++;
		long now = System.currentTimeMillis();
		if (now - fpsT >= 500) {
			fps = (int) Math.round(frames * 1000.0 / (now - fpsT));
			frames = 0;
			fpsT = now;
		}

		int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
		if (Cfg.FPS.enabled) fpsCounter(g, mc);
		if (Cfg.AIMHP.enabled) aimHp(g, mc, sw, sh);
		if (Cfg.ARMOR.enabled) armor(g, mc, sw, sh);
		Gfx.alphaMul = 1f;
	}

	private static int fpsColor(int f) {
		return f >= 100 ? 0xFF6BFF8A : f >= 60 ? 0xFFB8F26B : f >= 30 ? 0xFFFFD84D : 0xFFFF5A5A;
	}

	// ---------------- 3. FPS ----------------

	private static void fpsCounter(GuiGraphics g, Minecraft mc) {
		Font f = mc.font;
		String num = String.valueOf(fps);
		int x = 6, y = 6, w = f.width("FPS ") + f.width(num) + 20, h = 19;
		Gfx.panel(g, x, y, w, h);
		Gfx.vgrad(g, x + 4, y + 4, 2, 11, Gfx.hud(0f), Gfx.hud(0.6f));
		Gfx.gstr(g, f, "FPS", x + 11, y + 6, Gfx.hud(0f), Gfx.hud(0.5f), (System.currentTimeMillis() % 2500L) / 2500f);
		Gfx.str(g, f, num, x + 11 + f.width("FPS "), y + 6, fpsColor(fps));
	}

	// ---------------- 4. HP противника под прицелом ----------------

	private static LivingEntity lastAim;

	private static void aimHp(GuiGraphics g, Minecraft mc, int sw, int sh) {
		Font f = mc.font;
		LivingEntity e = mc.crosshairPickEntity instanceof LivingEntity le && le != mc.player ? le : null;
		if (e != null) {
			if (e != lastAim) {
				lastAim = e;
				Gfx.anim("aim.hp").at(Gfx.clamp01(e.getHealth() / Math.max(1f, e.getMaxHealth())));
				Gfx.anim("aim.trail").at(Gfx.clamp01(e.getHealth() / Math.max(1f, e.getMaxHealth())));
			}
		}
		float a = Gfx.anim("aim.a").to(e != null ? 1f : 0f, 14f);
		LivingEntity t = e != null ? e : lastAim;
		if (a < 0.02f || t == null) return;

		float hp = t.getHealth(), max = Math.max(1f, t.getMaxHealth());
		float frac = Gfx.clamp01(hp / max);
		Gfx.Anim hpA = Gfx.anim("aim.hp"), trA = Gfx.anim("aim.trail");
		float shown = hpA.to(frac, 14f);
		float trail = trA.to(frac, frac < trA.v ? 2.5f : 20f);

		if (Cfg.AH_STYLE.idx == 1) { // крупные цифры под прицелом: зелёный -> красный, ноль красный
			int val = Math.max(0, (int) Math.ceil(hp));
			String n = String.valueOf(val);
			int col = 0xFF000000 | Cfg.hsv(frac * 0.33f, 0.85f, 1f);
			float sc = 1.7f;
			Gfx.alphaMul = a;
			Gfx.strS(g, f, n, sw / 2f - f.width(n) * sc / 2f + 1f, sh / 2f + 11f, 0xFF000000, sc); // тёмная подложка
			Gfx.strS(g, f, n, sw / 2f - f.width(n) * sc / 2f, sh / 2f + 10f, col, sc);
			Gfx.alphaMul = 1f;
			return;
		}
		String name = t.getName().getString();
		String num = String.format("%.1f / %.0f", hp, max);
		int w = Math.max(96, Math.max(f.width(name), f.width(num)) + 22);
		int h = 12 + (name.isEmpty() ? 0 : 11) + (num.isEmpty() ? 0 : 11);
		int x = sw / 2 - w / 2, y = sh / 2 + 16 + Math.round((1f - a) * 6f);
		Gfx.alphaMul = a;
		Gfx.panel(g, x, y, w, h);
		int ty = y + 5;
		int hc = 0xFF000000 | Cfg.hsv(shown * 0.33f, 0.8f, 1f);
		if (!name.isEmpty()) {
			Gfx.strC(g, f, Gfx.trim(f, name, w - 12), x + w / 2, ty, WHITE);
			ty += 11;
		}
		int bx = x + 8, bw = w - 16, by = ty + 1;
		Gfx.rr(g, bx, by, bw, 5, 2, 0xFF23232B);
		if (trail > shown + 0.004f) Gfx.rr(g, bx, by, Math.max(3, (int) (bw * trail)), 5, 2, 0xB0FFFFFF);
		Gfx.rr(g, bx, by, Math.max(3, (int) (bw * shown)), 5, 2, hc);
		if (!num.isEmpty()) Gfx.strC(g, f, num, x + w / 2, by + 8, GRAY);
		Gfx.alphaMul = 1f;
	}

	// ---------------- 5. Armor Status ----------------

	private static void armor(GuiGraphics g, Minecraft mc, int sw, int sh) {
		Font f = mc.font;
		EquipmentSlot[] sl = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
		boolean any = false;
		for (EquipmentSlot s : sl) if (!mc.player.getItemBySlot(s).isEmpty()) any = true;
		if (!any) return;
		boolean vert = Cfg.AR_VERT.v, dur = Cfg.AR_DUR.v;
		int slot = 24, pw = dur ? 30 : 0;
		int w = vert ? 24 + pw : 4 * slot, h = vert ? 4 * slot : 24;
		int x = vert ? sw - 6 - w : sw / 2 - w / 2, y = vert ? sh / 2 - h / 2 : sh - 72;
		for (int i = 0; i < 4; i++) {
			ItemStack s = mc.player.getItemBySlot(sl[i]);
			int sx = vert ? x + pw : x + i * slot, sy = vert ? y + i * slot : y;
			if (s.isEmpty()) {
				Gfx.ring(g, sx + 1, sy + 1, 22, 22, 4, 1, 0x30FFFFFF);
				continue;
			}
			Gfx.panel(g, sx + 1, sy + 1, 22, 22);
			g.renderItem(s, sx + 4, sy + 4);
			float fr = 1f;
			if (s.isDamageableItem() && s.getMaxDamage() > 0) {
				fr = Math.max(0f, 1f - (float) s.getDamageValue() / s.getMaxDamage());
				int c = 0xFF000000 | Cfg.hsv(fr * 0.33f, 0.85f, 1f);
				Gfx.rect(g, sx + 4, sy + 19, sx + 20, sy + 21, 0xFF202020);
				Gfx.rect(g, sx + 4, sy + 19, sx + 4 + Math.max(1, (int) (16 * fr)), sy + 21, c);
				if (dur && vert) {
					String p = Math.round(fr * 100) + "%";
					Gfx.str(g, f, p, sx - 3 - f.width(p), sy + 8, c);
				} else if (dur) {
					String p = Math.round(fr * 100) + "%";
					Gfx.strC(g, f, p, sx + 12, sy - 10, c);
				}
			}
		}
	}
}
