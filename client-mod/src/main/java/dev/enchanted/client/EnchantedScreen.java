package dev.enchanted.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Меню: боковая панель, карточки модулей с переключателями и шестерёнкой настроек. */
public class EnchantedScreen extends Screen {
	private static final int W = 540, H = 330, SIDE = 116;
	private static final String[] TABS = {"Visuals", "HUD", "Render", "Тема"};
	private static final int WHITE = 0xFFFFFFFF, GRAY = 0xFF9A9A9A, OFF = 0xFF3A3A3A;

	private static int tab = 0;
	private Cfg.Module open;
	private int px, py, pw, ph;
	private final List<Hit> hits = new ArrayList<>();
	private Cfg.Slider dragging;
	private int dragX, dragW;

	private record Hit(int x, int y, int w, int h, Runnable action) {
		boolean in(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	public EnchantedScreen() {
		super(Component.literal("Enchanted"));
	}

	@Override
	protected void init() {
		Cfg.syncFromOptions();
		SkyLib.scan(); // подхватить новые файлы из папки skies
		Cfg.SKY_PRESET.opts = SkyLib.names();
		if (Cfg.SKY_PRESET.idx >= Cfg.SKY_PRESET.opts.length) Cfg.SKY_PRESET.idx = 0;
	}

	// ---------- рисование ----------

	/** Скруглённый прямоугольник без перекрытий (корректен и для полупрозрачных цветов). */
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

	private void text(GuiGraphics g, String s, int x, int y, int c) {
		g.drawString(this.font, s, x, y, c);
	}

	private String trim(String s, int maxW) {
		if (this.font.width(s) <= maxW) return s;
		while (s.length() > 1 && this.font.width(s + "..") > maxW) s = s.substring(0, s.length() - 1);
		return s + "..";
	}

	private void toggle(GuiGraphics g, int x, int y, boolean on, int ac) {
		rr(g, x, y, 24, 11, 5, on ? ac : OFF);
		rr(g, on ? x + 14 : x + 2, y + 2, 7, 7, 3, on && ((ac >> 8) & 255) > 0xB0 ? 0xFF111111 : WHITE);
	}

	private static int mix(int a, int b, float t) {
		int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int gr = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
		return 0xFF000000 | (r << 16) | (gr << 8) | bl;
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float delta) {
		hits.clear();
		pw = Math.min(W, this.width - 16);
		ph = Math.min(H, this.height - 16);
		px = (this.width - pw) / 2;
		py = (this.height - ph) / 2;
		int ac = Cfg.ACCENT[Cfg.theme];

		g.fill(0, 0, this.width, this.height, 0x88000000);
		rr(g, px - 1, py - 1, pw + 2, ph + 2, 9, (ac & 0xFFFFFF) | 0x66000000);
		rr(g, px, py, pw, ph, 8, 0xFF0B0B0B);
		rr(g, px + 6, py + 6, SIDE - 6, ph - 12, 6, 0xFF141414);

		text(g, "ENCHANTED", px + 16, py + 16, ac);
		text(g, "client", px + 16, py + 27, GRAY);

		for (int i = 0; i < TABS.length; i++) {
			int ty = py + 48 + i * 26, tx = px + 12, tw = SIDE - 18;
			boolean sel = tab == i && open == null || (open != null && tab == i);
			boolean hov = mx >= tx && mx < tx + tw && my >= ty && my < ty + 22;
			if (sel) {
				rr(g, tx, ty, tw, 22, 5, mix(0xFF141414, ac, 0.22f));
				g.fill(tx, ty + 5, tx + 2, ty + 17, ac);
			} else if (hov) {
				rr(g, tx, ty, tw, 22, 5, 0xFF1C1C1C);
			}
			text(g, TABS[i], tx + 10, ty + 7, sel ? WHITE : GRAY);
			final int idx = i;
			hits.add(new Hit(tx, ty, tw, 22, () -> { tab = idx; open = null; }));
		}

		int cx = px + SIDE + 8, cy = py + 14, cw = pw - SIDE - 22, ch = ph - 28;
		String title = open != null ? open.name : TABS[tab];
		text(g, title, cx, cy, WHITE);

		if (tab == 3) {
			renderThemes(g, mx, my, cx, cy + 24, cw, ac);
		} else if (open != null) {
			renderSettings(g, mx, my, cx, cy, cw, ac);
		} else {
			renderCards(g, mx, my, cx, cy + 22, cw, ac);
		}
	}

	private void renderCards(GuiGraphics g, int mx, int my, int cx, int y0, int cw, int ac) {
		String cat = tab == 0 ? Cfg.VIS : tab == 1 ? Cfg.HUD : Cfg.REN;
		int cols = cw >= 360 ? 3 : 2, gap = 8, cardW = (cw - (cols - 1) * gap) / cols, cardH = 60;
		int i = 0;
		for (Cfg.Module m : Cfg.ALL) {
			if (!m.cat.equals(cat)) continue;
			int x = cx + (i % cols) * (cardW + gap), y = y0 + (i / cols) * (cardH + gap);
			i++;
			boolean hov = mx >= x && mx < x + cardW && my >= y && my < y + cardH;
			rr(g, x, y, cardW, cardH, 6, m.enabled ? mix(0xFF181818, ac, 0.16f) : (hov ? 0xFF262626 : 0xFF181818));
			text(g, trim(m.name, cardW - 16), x + 8, y + 8, WHITE);
			text(g, trim(m.desc, cardW - 16), x + 8, y + 21, GRAY);
			toggle(g, x + 8, y + cardH - 18, m.enabled, ac);
			hits.add(new Hit(x, y, cardW, cardH, () -> { m.enabled = !m.enabled; Cfg.changed(m); }));
			if (!m.settings.isEmpty()) {
				int gx = x + cardW - 22, gy = y + cardH - 19;
				boolean gh = mx >= gx - 3 && mx < gx + 19 && my >= gy - 3 && my < gy + 14;
				int dc = gh ? ac : GRAY;
				for (int d = 0; d < 3; d++) g.fill(gx + d * 6, gy + 5, gx + d * 6 + 3, gy + 8, dc);
				hits.add(new Hit(gx - 3, gy - 3, 22, 17, () -> open = m));
			}
		}
	}

	private void renderSettings(GuiGraphics g, int mx, int my, int cx, int cy, int cw, int ac) {
		Cfg.Module m = open;
		// назад + главный переключатель
		int bx = cx + cw - 56;
		boolean bh = mx >= bx && mx < bx + 56 && my >= cy - 3 && my < cy + 13;
		rr(g, bx, cy - 3, 56, 16, 5, bh ? 0xFF2C2C2C : 0xFF1C1C1C);
		text(g, "< Назад", bx + 8, cy + 1, WHITE);
		hits.add(new Hit(bx, cy - 3, 56, 16, () -> open = null));
		toggle(g, bx - 34, cy - 1, m.enabled, ac);
		hits.add(new Hit(bx - 36, cy - 3, 30, 16, () -> { m.enabled = !m.enabled; Cfg.changed(m); }));

		int y = cy + 28;
		int ctrlX = cx + (int) (cw * 0.42), ctrlW = cw - (int) (cw * 0.42) - 4;
		for (Cfg.Setting s : m.settings) {
			int hgt = s instanceof Cfg.Color ? 82 : 26;
			rr(g, cx, y - 4, cw, hgt, 5, 0xFF141414);
			text(g, trim(s.name, ctrlX - cx - 12), cx + 8, y + 5, WHITE);
			if (s instanceof Cfg.Bool b) {
				toggle(g, ctrlX + ctrlW - 26, y + 3, b.v, ac);
				hits.add(new Hit(ctrlX, y - 4, ctrlW, 26, () -> { b.v = !b.v; Cfg.changed(m); }));
			} else if (s instanceof Cfg.Slider sl) {
				int vw = 46, tx = ctrlX, tw = ctrlW - vw - 8;
				rr(g, tx, y + 7, tw, 4, 2, OFF);
				float f = (float) ((sl.v - sl.min) / (sl.max - sl.min));
				rr(g, tx, y + 7, Math.max(4, (int) (tw * f)), 4, 2, ac);
				rr(g, tx + (int) ((tw - 6) * f), y + 4, 6, 10, 3, WHITE);
				String val = Math.round(sl.v) + sl.unit;
				text(g, val, tx + tw + 8, y + 5, GRAY);
				hits.add(new Hit(tx - 3, y - 2, tw + 6, 22, () -> {
					dragging = sl;
					dragX = tx;
					dragW = tw;
					dragTo(mx);
				}));
			} else if (s instanceof Cfg.Color c) {
				g.fill(ctrlX, y + 2, ctrlX + 22, y + 16, 0xFF000000 | c.rgb());
				text(g, "Радуга", ctrlX + 30, y + 5, GRAY);
				toggle(g, ctrlX + ctrlW - 26, y + 3, c.idx == Cfg.RAINBOW, ac);
				hits.add(new Hit(ctrlX + 26, y - 4, ctrlW - 26, 26, () -> { c.idx = c.idx == Cfg.RAINBOW ? 0 : Cfg.RAINBOW; Cfg.changed(m); }));
				Cfg.Slider[] rgb = {c.sr, c.sg, c.sb};
				String[] ln = {"R", "G", "B"};
				int[] lc = {0xFFFF6A6A, 0xFF6AFF8A, 0xFF6A9AFF};
				for (int q = 0; q < 3; q++) {
					int sy = y + 24 + q * 18;
					text(g, ln[q], cx + 10, sy + 5, lc[q]);
					slider(g, rgb[q], cx + 26, sy, cw - 26 - 50, ac, mx, () -> c.idx = 0);
				}
			} else if (s instanceof Cfg.Choice ch) {
				int bw = 150, bx2 = ctrlX + ctrlW - bw;
				rr(g, bx2, y + 1, bw, 16, 5, 0xFF262626);
				String v = trim(ch.opts[Math.floorMod(ch.idx, ch.opts.length)], bw - 30);
				text(g, "<", bx2 + 6, y + 5, ac);
				text(g, ">", bx2 + bw - 11, y + 5, ac);
				text(g, v, bx2 + bw / 2 - this.font.width(v) / 2, y + 5, WHITE);
				hits.add(new Hit(bx2, y + 1, bw / 2, 16, () -> { ch.idx = Math.floorMod(ch.idx - 1, ch.opts.length); Cfg.changed(m); }));
				hits.add(new Hit(bx2 + bw / 2, y + 1, bw / 2, 16, () -> { ch.idx = Math.floorMod(ch.idx + 1, ch.opts.length); Cfg.changed(m); }));
			}
			y += hgt + 4;
		}
	}

	private void slider(GuiGraphics g, Cfg.Slider sl, int tx, int y, int tw, int ac, int mx, Runnable pre) {
		rr(g, tx, y + 7, tw, 4, 2, OFF);
		float f = (float) ((sl.v - sl.min) / (sl.max - sl.min));
		rr(g, tx, y + 7, Math.max(4, (int) (tw * f)), 4, 2, ac);
		rr(g, tx + (int) ((tw - 6) * f), y + 4, 6, 10, 3, WHITE);
		text(g, Math.round(sl.v) + sl.unit, tx + tw + 8, y + 5, GRAY);
		hits.add(new Hit(tx - 3, y - 2, tw + 6, 22, () -> {
			pre.run();
			dragging = sl;
			dragX = tx;
			dragW = tw;
			dragTo(mx);
		}));
	}

	private void renderThemes(GuiGraphics g, int mx, int my, int cx, int y0, int cw, int ac) {
		text(g, "Цвет интерфейса", cx, y0, GRAY);
		int y = y0 + 16, w = Math.min(96, (cw - 4 * 8) / 5);
		for (int i = 0; i < Cfg.THEMES.length; i++) {
			int x = cx + i * (w + 8);
			boolean sel = Cfg.theme == i;
			boolean hov = mx >= x && mx < x + w && my >= y && my < y + 64;
			rr(g, x, y, w, 64, 6, sel ? mix(0xFF181818, Cfg.ACCENT[i], 0.2f) : (hov ? 0xFF262626 : 0xFF181818));
			rr(g, x + w / 2 - 12, y + 10, 24, 24, 12, Cfg.ACCENT[i]);
			String n = trim(Cfg.THEMES[i], w - 8);
			text(g, n, x + w / 2 - this.font.width(n) / 2, y + 42, sel ? WHITE : GRAY);
			final int idx = i;
			hits.add(new Hit(x, y, w, 64, () -> { Cfg.theme = idx; Cfg.save(); }));
		}
		text(g, "Меню открывается правым Shift. Esc - закрыть.", cx, y + 84, GRAY);
	}

	// ---------- ввод ----------

	private void dragTo(double mouseX) {
		if (dragging == null || dragW <= 0 || open == null) return;
		double f = Math.max(0, Math.min(1, (mouseX - dragX) / dragW));
		dragging.set(dragging.min + f * (dragging.max - dragging.min));
		Cfg.changed(open);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0) return super.mouseClicked(event, doubleClick);
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit h = hits.get(i);
			if (h.in(event.x(), event.y())) {
				h.action().run();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging != null) {
			dragTo(event.x());
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging != null) {
			dragging = null;
			Cfg.save();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		Cfg.save();
		Minecraft.getInstance().options.save();
		super.onClose();
	}
}
