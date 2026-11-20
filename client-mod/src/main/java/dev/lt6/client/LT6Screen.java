package dev.lt6.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Меню: боковая панель с анимированным выбором, поиск, карточки модулей, настройки с прокруткой, клавиши-переключатели,
 * частицы на фоне и плавное появление. Рисуется через Gfx (GuiGraphics), ванильный блюр фона сохраняется.
 */
public class LT6Screen extends Screen {
	private static final int W = 580, H = 352, SIDE = 126;
	private static final String[] TABS = {"Visuals", "Utils", "Player", "Theme"};
	private static final int WHITE = 0xFFFFFFFF, GRAY = 0xFF9A9A9A, DIM = 0xFF6A6A72;
	private static final int CARD = 0xFF16161C, ROW = 0xFF13131A, SIDEBG = 0xAA101016;

	private static int tab = 0;
	private Cfg.Module open;
	private String query = "";
	private boolean searchFocus;
	private Cfg.Module listening; // модуль, которому сейчас назначается клавиша
	private final Gfx.Anim openAnim = new Gfx.Anim().at(0f);
	private float scrollTarget, contentH;
	private int px, py, pw, ph, listY0, listY1;
	private int clipX0, clipY0, clipX1, clipY1;
	private boolean clipOn;
	private double lastClickX;
	private final List<Hit> hits = new ArrayList<>();
	private Cfg.Slider dragging;
	private Cfg.Module dragMod;
	private int dragX, dragW;

	private record Hit(int x, int y, int w, int h, Runnable left, Runnable right) {
		boolean in(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	public LT6Screen() {
		super(Component.literal("LT6"));
	}

	@Override
	protected void init() {
		// Custom Sky packs are installed by the launcher; select one in Minecraft resource packs.
		Cfg.SKY_PRESET.opts = SkyLib.names();
		if (Cfg.SKY_PRESET.idx >= Cfg.SKY_PRESET.opts.length) Cfg.SKY_PRESET.idx = 0;
		resetScroll();
	}

	private void resetScroll() {
		scrollTarget = 0f;
		Gfx.anim("scroll").at(0f);
	}

	/** Ванильный блюр без ванильного затемнения: затемнение рисуем сами. */
	@Override
	public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
		if (Cfg.UI_BLUR.v) renderBlurredBackground(g); // ванильный блюр - отдельный проход по экрану, по умолчанию выключен
	}

	// ---------- регистрация нажатий ----------

	private void hit(int x, int y, int w, int h, Runnable left) {
		hit(x, y, w, h, left, null);
	}

	private void hit(int x, int y, int w, int h, Runnable left, Runnable right) {
		if (clipOn) { // нажатия считаются только в видимой (не обрезанной прокруткой) части
			int x1 = Math.max(x, clipX0), y1 = Math.max(y, clipY0);
			int x2 = Math.min(x + w, clipX1), y2 = Math.min(y + h, clipY1);
			if (x2 <= x1 || y2 <= y1) return;
			x = x1;
			y = y1;
			w = x2 - x1;
			h = y2 - y1;
		}
		hits.add(new Hit(x, y, w, h, left, right));
	}

	private boolean over(int mx, int my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h && my >= listY0 && my < listY1;
	}

	// ---------- кадр ----------

	@Override
	public void render(GuiGraphics g, int mx, int my, float delta) {
		hits.clear();
		clipOn = false;
		Font f = this.font;
		long now = System.currentTimeMillis();
		float o = openAnim.to(1f, 11f);
		Gfx.alphaMul = o;

		pw = Math.min(W, this.width - 16);
		ph = Math.min(H, this.height - 16);
		px = (this.width - pw) / 2;
		py = (this.height - ph) / 2 + Math.round((1f - o) * 18f);
		int ac = Cfg.accent(), ac2 = Cfg.accent2();

		Gfx.vgrad(g, 0, 0, this.width, this.height, 0x44000000, 0x99000000);
		if (Cfg.UI_PART.v) particles(g, ac);
		// A neutral panel shadow is always visible, even with decorative glow disabled.
		Gfx.glow(g, px, py, pw, ph, 10, 0x000000, 0.65f, 6);
		if (Cfg.UI_GLOW.v) Gfx.glow(g, px, py, pw, ph, 10, ac & 0xFFFFFF, 0.35f, 5);
		Gfx.rr(g, px, py, pw, ph, 10, 0xE60A0A10);
		Gfx.ring(g, px, py, pw, ph, 10, 1, Gfx.alpha(ac & 0xFFFFFF, 64));
		Gfx.hgrad(g, px + 16, py, pw - 32, 1, Gfx.a(ac, 0.9f), Gfx.a(ac2, 0.9f));

		sidebar(g, f, mx, my, now, ac, ac2);

		int cx = px + SIDE + 10, cw = pw - SIDE - 20, hy = py + 14;
		listY0 = py + 46;
		listY1 = py + ph - 26;

		if (open != null) headerSettings(g, f, mx, my, cx, cw, hy, ac);
		else headerList(g, f, cx, cw, hy, now, ac);

		float view = listY1 - listY0;
		scrollTarget = Math.max(0f, Math.min(Math.max(0f, contentH - view), scrollTarget));
		float scroll = Gfx.anim("scroll").to(scrollTarget, 16f);
		int sc = Math.round(scroll);

		g.enableScissor(cx - 4, listY0, cx + cw + 4, listY1);
		clipOn = true;
		clipX0 = cx - 4;
		clipY0 = listY0;
		clipX1 = cx + cw + 4;
		clipY1 = listY1;
		int h;
		if (open != null) h = renderSettings(g, mx, my, cx, listY0, cw, sc, open, open.settings, true, ac, ac2);
		else if (!query.isEmpty() || tab != 3) h = renderCards(g, mx, my, cx, listY0, cw, sc, ac, ac2);
		else h = renderThemes(g, mx, my, cx, listY0, cw, sc, ac, ac2);
		contentH = h;
		g.disableScissor();
		clipOn = false;

		if (contentH > view + 1f) { // полоса прокрутки
			int trackX = px + pw - 8;
			Gfx.rr(g, trackX, listY0, 3, (int) view, 1, 0x18FFFFFF);
			int th = Math.max(16, (int) (view * view / contentH));
			int ty = listY0 + (int) ((view - th) * (scroll / Math.max(1f, contentH - view)));
			Gfx.rr(g, trackX, ty, 3, th, 1, Gfx.a(ac, 0.8f));
		}

		String hint = open != null
				? "Клик по клавише - назначить, ПКМ - сбросить  |  Esc - закрыть"
				: "ПКМ по карточке - настройки  |  колесо - прокрутка  |  Esc - закрыть";
		Gfx.str(g, f, Gfx.trim(f, hint, cw), cx, py + ph - 18, DIM);
		Gfx.alphaMul = 1f;
	}

	// ---------- боковая панель ----------

	private void sidebar(GuiGraphics g, Font f, int mx, int my, long now, int ac, int ac2) {
		Gfx.rr(g, px + 6, py + 6, SIDE - 6, ph - 12, 8, SIDEBG);
		Gfx.gstr(g, f, "LT6", px + 17, py + 17, ac, ac2, (now % 3200L) / 3200f);
		Gfx.str(g, f, "client  2.2", px + 17, py + 28, DIM);

		int tx = px + 12, tw = SIDE - 18, ty0 = py + 48;
		int selY = Math.round(Gfx.anim("tabsel").to(ty0 + tab * 26, 16f));
		Gfx.rr(g, tx, selY, tw, 22, 6, Gfx.mix(0xFF111116, ac, 0.22f));
		Gfx.vgrad(g, tx, selY + 5, 2, 12, ac, ac2);
		for (int i = 0; i < TABS.length; i++) {
			int ty = ty0 + i * 26;
			boolean hov = mx >= tx && mx < tx + tw && my >= ty && my < ty + 22;
			float hv = Gfx.anim(TABS[i], "tab").to(hov ? 1f : 0f, 14f);
			float sv = Gfx.anim(TABS[i], "tabon").to(tab == i ? 1f : 0f, 14f);
			if (hv > 0.02f && tab != i) Gfx.rr(g, tx, ty, tw, 22, 6, Gfx.alpha(0xFFFFFF, (int) (14 * hv)));
			int tc = Gfx.mix(GRAY, WHITE, Math.max(hv * 0.7f, sv));
			Gfx.str(g, f, TABS[i], tx + 15, ty + 7, tc);
			final int idx = i;
			hit(tx, ty, tw, 22, () -> {
				tab = idx;
				open = null;
				query = "";
				searchFocus = false;
				resetScroll();
			});
		}

		Gfx.str(g, f, "Меню: правый Shift", px + 17, py + ph - 26, DIM);
	}

	private void icon(GuiGraphics g, int i, int x, int y, int c) {
		switch (i) {
			case 0 -> { // глаз
				Gfx.rr(g, x, y + 2, 12, 8, 4, c);
				Gfx.rr(g, x + 3, y + 4, 6, 4, 2, 0xFF101015);
				Gfx.rect(g, x + 5, y + 5, x + 7, y + 7, c);
			}
			case 1 -> { // панели HUD
				Gfx.rr(g, x, y + 1, 12, 3, 1, c);
				Gfx.rr(g, x, y + 5, 7, 3, 1, c);
				Gfx.rr(g, x, y + 9, 12, 3, 1, c);
			}
			default -> { // капля темы
				Gfx.rr(g, x, y, 12, 12, 6, c);
				Gfx.rr(g, x + 6, y + 6, 6, 6, 3, Cfg.accent2());
			}
		}
	}

	// ---------- заголовки ----------

	private void headerList(GuiGraphics g, Font f, int cx, int cw, int hy, long now, int ac) {
		String title = !query.isEmpty() ? "Поиск" : tab == 3 ? "Theme" : TABS[tab];
		Gfx.str(g, f, title, cx, hy + 1, WHITE);

		int sbw = 180, sbx = cx + cw - sbw, sby = hy - 3;
		float fa = Gfx.anim("search").to(searchFocus ? 1f : 0f, 14f);
		Gfx.rr(g, sbx, sby, sbw, 18, 6, Gfx.mix(0xFF14141A, 0xFF1B1B24, fa));
		Gfx.ring(g, sbx, sby, sbw, 18, 6, 1, Gfx.mix(0x18FFFFFF, Gfx.alpha(ac & 0xFFFFFF, 190), fa));
		Gfx.ring(g, sbx + 7, sby + 5, 7, 7, 3, 1, GRAY); // лупа
		Gfx.rect(g, sbx + 13, sby + 11, sbx + 15, sby + 13, GRAY);
		String shown = query.isEmpty() ? (searchFocus ? "" : "Поиск модулей") : query;
		String vis = Gfx.trim(f, shown, sbw - 34);
		int tx = sbx + 20;
		Gfx.str(g, f, vis, tx, sby + 5, query.isEmpty() ? DIM : WHITE);
		if (searchFocus && (now / 500L) % 2L == 0L) {
			int cxp = tx + f.width(vis) + 1;
			Gfx.rect(g, cxp, sby + 4, cxp + 1, sby + 14, ac);
		}
		hit(sbx, sby, sbw, 18, () -> searchFocus = true);
	}

	private void headerSettings(GuiGraphics g, Font f, int mx, int my, int cx, int cw, int hy, int ac) {
		Cfg.Module m = open;
		Gfx.str(g, f, m.name, cx, hy + 1, WHITE);
		int bx = cx + cw - 62;
		button(g, f, mx, my, bx, hy - 4, 62, 18, "< Назад", "back", () -> {
			open = null;
			listening = null;
			resetScroll();
		});
		float on = Gfx.anim(m, "on").to(m.enabled ? 1f : 0f, 12f);
		Gfx.toggle(g, bx - 36, hy - 1, on, ac);
		hit(bx - 40, hy - 4, 36, 18, () -> Cfg.toggle(m));
		button(g, f, mx, my, bx - 40 - 62, hy - 4, 58, 18, "Сброс", "reset", () -> {
			for (Cfg.Setting s : m.settings) s.reset();
			m.key = 0;
			Cfg.changed(m);
		});
	}

	private void button(GuiGraphics g, Font f, int mx, int my, int x, int y, int w, int h, String label, String id, Runnable r) {
		boolean hov = mx >= x && mx < x + w && my >= y && my < y + h;
		float hv = Gfx.anim(id, "btn").to(hov ? 1f : 0f, 14f);
		Gfx.rr(g, x, y, w, h, 6, Gfx.mix(0xFF1A1A22, 0xFF2A2A34, hv));
		Gfx.ring(g, x, y, w, h, 6, 1, 0x14FFFFFF);
		Gfx.str(g, f, label, x + (w - f.width(label)) / 2, y + (h - 8) / 2, Gfx.mix(GRAY, WHITE, hv));
		hit(x, y, w, h, r);
	}

	// ---------- карточки модулей ----------

	private List<Cfg.Module> visCache;
	private int visTab = -1;
	private String visQuery = null;

	private List<Cfg.Module> visible() {
		if (visCache != null && visTab == tab && query.equals(visQuery)) return visCache;
		visTab = tab;
		visQuery = query;
		List<Cfg.Module> out = new ArrayList<>();
		String q = query.trim().toLowerCase(Locale.ROOT);
		String cat = tab == 0 ? Cfg.VIS : tab == 1 ? Cfg.HUD : Cfg.PLAYER;
		for (Cfg.Module m : Cfg.ALL) {
			boolean ok = q.isEmpty()
					? m.cat.equals(cat)
					: m.name.toLowerCase(Locale.ROOT).contains(q) || m.desc.toLowerCase(Locale.ROOT).contains(q);
			if (ok) out.add(m);
		}
		visCache = out;
		return out;
	}

	private int renderCards(GuiGraphics g, int mx, int my, int cx, int y0, int cw, int scroll, int ac, int ac2) {
		Font f = this.font;
		List<Cfg.Module> mods = visible();
		if (mods.isEmpty()) {
			Gfx.strC(g, f, "Ничего не найдено", cx + cw / 2, y0 + 30, DIM);
			return 0;
		}
		int cols = cw >= 400 ? 3 : 2, gap = 8, cardW = (cw - (cols - 1) * gap) / cols, cardH = 66;
		boolean searching = !query.isEmpty();
		for (int i = 0; i < mods.size(); i++) {
			Cfg.Module m = mods.get(i);
			int x = cx + (i % cols) * (cardW + gap), y = y0 + (i / cols) * (cardH + gap) - scroll;
			if (y + cardH < listY0 || y > listY1) continue;

			float hv = Gfx.anim(m, "h").to(over(mx, my, x, y, cardW, cardH) ? 1f : 0f, 14f);
			float on = Gfx.anim(m, "on").to(m.enabled ? 1f : 0f, 12f);
			int base = Gfx.mix(CARD, 0xFF1F1F28, hv);
			Gfx.rr(g, x, y, cardW, cardH, 7, Gfx.mix(base, (ac & 0xFFFFFF) | 0xFF000000, 0.13f * on));
			Gfx.ring(g, x, y, cardW, cardH, 7, 1, Gfx.mix(Gfx.alpha(0xFFFFFF, 16 + (int) (14 * hv)), Gfx.alpha(ac & 0xFFFFFF, 120), on));
			if (on > 0.02f) Gfx.vgrad(g, x + 1, y + 9, 2, cardH - 18, Gfx.a(ac, on), Gfx.a(ac2, on));

			Gfx.str(g, f, Gfx.trim(f, m.name, cardW - 18), x + 10, y + 9, WHITE);
			Gfx.str(g, f, Gfx.trim(f, m.desc, cardW - 18), x + 10, y + 22, GRAY);
			if (searching) {
				Gfx.str(g, f, m.cat, x + cardW - 10 - f.width(m.cat), y + 9, DIM);
			}
			Gfx.toggle(g, x + 10, y + cardH - 20, on, ac);

			int dx = x + cardW - 24, dy = y + cardH - 17;
			boolean dh = over(mx, my, dx - 5, dy - 5, 25, 17);
			float dv = Gfx.anim(m, "dots").to(dh ? 1f : 0f, 16f);
			int dc = Gfx.mix(GRAY, ac, dv);
			for (int d = 0; d < 3; d++) Gfx.rect(g, dx + d * 6, dy + 4, dx + d * 6 + 3, dy + 7, dc);
			if (m.key > 0) {
				String kn = "[" + Cfg.keyName(m.key) + "]";
				Gfx.str(g, f, kn, dx - 6 - f.width(kn), dy + 1, DIM);
			}

			hit(x, y, cardW, cardH, () -> Cfg.toggle(m), () -> openSettings(m));
			hit(dx - 5, dy - 5, 25, 17, () -> openSettings(m));
		}
		int rows = (mods.size() + cols - 1) / cols;
		return rows * (cardH + gap);
	}

	private void openSettings(Cfg.Module m) {
		open = m;
		listening = null;
		searchFocus = false;
		resetScroll();
	}

	// ---------- строки настроек ----------

	private void sliderTrack(GuiGraphics g, Cfg.Slider sl, int tx, int ty, int tw, int c0, int c1) {
		float fr = Gfx.clamp01((float) ((sl.v - sl.min) / (sl.max - sl.min)));
		Gfx.rr(g, tx, ty, tw, 2, 1, 0xFF2A2A33);
		Gfx.hgrad(g, tx, ty, Math.max(2, (int) (tw * fr)), 2, c0, c1);
		Gfx.rr(g, tx + (int) ((tw - 5) * fr), ty - 2, 5, 6, 2, WHITE);
	}

	private void startDrag(Cfg.Slider sl, Cfg.Module m, int tx, int tw) {
		dragging = sl;
		dragMod = m;
		dragX = tx;
		dragW = tw;
		dragTo(lastClickX);
	}

	private int renderSettings(GuiGraphics g, int mx, int my, int cx, int y0, int cw, int scroll, Cfg.Module m,
			List<Cfg.Setting> list, boolean withBind, int ac, int ac2) {
		Font f = this.font;
		int y = y0 - scroll, total = 0;
		int ctrlX = cx + (int) (cw * 0.40), ctrlW = cw - (int) (cw * 0.40) - 8;

		if (withBind) {
			int hh = 28;
			if (y + hh >= listY0 && y <= listY1) {
				Gfx.rr(g, cx, y, cw, hh, 6, ROW);
				Gfx.str(g, f, "Клавиша", cx + 10, y + 10, WHITE);
				boolean lis = listening == m;
				float la = Gfx.anim(m, "bind").to(lis ? 1f : 0f, 14f);
				String lab = lis ? "Нажми клавишу..." : Cfg.keyName(m.key);
				int bw = 120, bx = ctrlX + ctrlW - bw;
				Gfx.rr(g, bx, y + 5, bw, 18, 6, Gfx.mix(0xFF22222B, Gfx.mix(0xFF22222B, ac, 0.35f), la));
				Gfx.ring(g, bx, y + 5, bw, 18, 6, 1, Gfx.mix(0x18FFFFFF, Gfx.alpha(ac & 0xFFFFFF, 220), la));
				Gfx.strC(g, f, lab, bx + bw / 2, y + 10, m.key > 0 || lis ? WHITE : GRAY);
				hit(bx, y + 5, bw, 18, () -> listening = m, () -> {
					m.key = 0;
					Cfg.save();
				});
			}
			y += hh + 5;
			total += hh + 5;
		}

		for (Cfg.Setting s : list) {
			int hh = s instanceof Cfg.Color ? 112 : 28;
			if (y + hh < listY0 || y > listY1) {
				y += hh + 5;
				total += hh + 5;
				continue;
			}
			Gfx.rr(g, cx, y, cw, hh, 6, ROW);
			boolean tall = s instanceof Cfg.Color;
			Gfx.str(g, f, Gfx.trim(f, s.name, ctrlX - cx - 16), cx + 10, y + (tall ? 8 : 10), WHITE);

			if (s instanceof Cfg.Bool b) {
				float t = Gfx.anim(s, "b").to(b.v ? 1f : 0f, 14f);
				Gfx.toggle(g, ctrlX + ctrlW - 28, y + 8, t, ac);
				hit(cx, y, cw, hh, () -> {
					b.v = !b.v;
					Cfg.changed(m);
				});
			} else if (s instanceof Cfg.Slider sl) {
				int vw = 52, tx = ctrlX, tw = Math.min(156, ctrlW - vw - 8);
				sliderTrack(g, sl, tx, y + 12, tw, ac, ac2);
				Gfx.str(g, f, (sl.step < 0.1 ? String.format(Locale.ROOT, "%.2f", sl.v) : sl.step < 1 ? String.format(Locale.ROOT, "%.1f", sl.v) : String.valueOf(Math.round(sl.v))) + sl.unit, tx + tw + 8, y + 10, GRAY);
				hit(tx - 4, y + 2, tw + 8, 24, () -> startDrag(sl, m, tx, tw));
			} else if (s instanceof Cfg.Color col) {
				colorRow(g, f, m, col, cx, cw, ctrlX, ctrlW, y, ac);
			} else if (s instanceof Cfg.Choice ch) {
				int bw = Math.min(190, ctrlW), bx = ctrlX + ctrlW - bw;
				Gfx.rr(g, bx, y + 5, bw, 18, 6, 0xFF22222B);
				Gfx.ring(g, bx, y + 5, bw, 18, 6, 1, 0x14FFFFFF);
				String v = Gfx.trim(f, ch.opts[Math.floorMod(ch.idx, ch.opts.length)], bw - 34);
				Gfx.str(g, f, "<", bx + 8, y + 10, ac);
				Gfx.str(g, f, ">", bx + bw - 8 - f.width(">"), y + 10, ac);
				Gfx.strC(g, f, v, bx + bw / 2, y + 10, WHITE);
				hit(bx, y + 5, bw / 2, 18, () -> {
					ch.idx = Math.floorMod(ch.idx - 1, ch.opts.length);
					Cfg.changed(m);
				});
				hit(bx + bw / 2, y + 5, bw / 2, 18, () -> {
					ch.idx = Math.floorMod(ch.idx + 1, ch.opts.length);
					Cfg.changed(m);
				});
			}
			y += hh + 5;
			total += hh + 5;
		}
		return total;
	}

	private void colorRow(GuiGraphics g, Font f, Cfg.Module m, Cfg.Color col, int cx, int cw, int ctrlX, int ctrlW, int y, int ac) {
		int rgb = col.rgb();
		Gfx.rr(g, cx + 10, y + 22, 28, 14, 4, 0xFF000000 | rgb);
		Gfx.str(g, f, String.format("#%06X", rgb), cx + 44, y + 25, GRAY);

		float rt = Gfx.anim(col, "rb").to(col.idx == Cfg.RAINBOW ? 1f : 0f, 14f);
		String rl = "Радуга";
		Gfx.toggle(g, ctrlX + ctrlW - 28, y + 7, rt, ac);
		Gfx.str(g, f, rl, ctrlX + ctrlW - 28 - 8 - f.width(rl), y + 9, GRAY);
		hit(ctrlX + ctrlW - 40 - f.width(rl), y + 2, 40 + f.width(rl), 22, () -> {
			col.idx = col.idx == Cfg.RAINBOW ? 0 : Cfg.RAINBOW;
			Cfg.changed(m);
		});

		Cfg.Slider[] rgbS = {col.sr, col.sg, col.sb};
		String[] ln = {"R", "G", "B"};
		int[] lc = {0xFFFF6A6A, 0xFF6AFF8A, 0xFF6A9AFF};
		for (int q = 0; q < 3; q++) {
			int sy = y + 42 + q * 17, tx = cx + 26, tw = cw - 26 - 50;
			Cfg.Slider rs = rgbS[q];
			Gfx.str(g, f, ln[q], cx + 10, sy, lc[q]);
			sliderTrack(g, rs, tx, sy + 2, tw, lc[q], lc[q]);
			Gfx.str(g, f, String.valueOf((int) rs.v), tx + tw + 8, sy, GRAY);
			hit(tx - 4, sy - 4, tw + 8, 17, () -> {
				col.idx = 0;
				startDrag(rs, m, tx, tw);
			});
		}
		for (int i = 0; i < Cfg.CRGB.length - 1; i++) {
			int sx0 = cx + 10 + i * 20, sy0 = y + 94, ci = i;
			Gfx.rr(g, sx0, sy0, 16, 12, 4, 0xFF000000 | Cfg.CRGB[i]);
			hit(sx0, sy0, 16, 12, () -> {
				col.pick(Cfg.CRGB[ci]);
				Cfg.changed(m);
			});
		}
	}

	// ---------- вкладка «Тема» ----------

	private int renderThemes(GuiGraphics g, int mx, int my, int cx, int y0, int cw, int scroll, int ac, int ac2) {
		Font f = this.font;
		int y = y0 - scroll;
		Gfx.str(g, f, "Цвет интерфейса", cx + 2, y, GRAY);
		int sw0 = 96, gap = 8, shh = 66, cols = Math.max(1, (cw + gap) / (sw0 + gap)), gy = y + 16;
		for (int i = 0; i < Cfg.THEMES.length; i++) {
			int x = cx + (i % cols) * (sw0 + gap), yy = gy + (i / cols) * (shh + gap);
			if (yy + shh < listY0 || yy > listY1) continue;
			float hv = Gfx.anim(Cfg.THEMES[i], "th").to(over(mx, my, x, yy, sw0, shh) ? 1f : 0f, 14f);
			float sv = Gfx.anim(Cfg.THEMES[i], "ths").to(Cfg.theme == i ? 1f : 0f, 12f);
			int a1 = Cfg.ACCENT[i], a2 = Cfg.ACCENT2[i];
			Gfx.rr(g, x, yy, sw0, shh, 8, Gfx.mix(Gfx.mix(CARD, 0xFF22222C, hv), Gfx.mix(CARD, a1, 0.18f), sv));
			Gfx.ring(g, x, yy, sw0, shh, 8, 1, Gfx.mix(0x14FFFFFF, Gfx.alpha(a1 & 0xFFFFFF, 210), sv));
			int mid = x + sw0 / 2;
			Gfx.rr(g, mid - 13, yy + 9, 26, 26, 13, a1);
			Gfx.rr(g, mid + 2, yy + 22, 14, 14, 7, a2);
			Gfx.ring(g, mid + 2, yy + 22, 14, 14, 7, 1, CARD);
			Gfx.strC(g, f, Gfx.trim(f, Cfg.THEMES[i], sw0 - 8), mid, yy + 47, Gfx.mix(GRAY, WHITE, Math.max(hv, sv)));
			final int idx = i;
			hit(x, yy, sw0, shh, () -> {
				Cfg.theme = idx;
				Cfg.save();
			});
		}
		int rows = (Cfg.THEMES.length + cols - 1) / cols;
		int after = 16 + rows * (shh + gap) + 6;
		Gfx.str(g, f, "Интерфейс", cx + 2, y + after, GRAY);
		after += 16;
		int rh = renderSettings(g, mx, my, cx, y0 + after, cw, scroll, Cfg.STYLE, Cfg.STYLE.settings, false, ac, ac2);
		return after + rh;
	}

	// ---------- частицы фона ----------

	private static final float[][] PART = new float[24][5];

	static {
		Random r = new Random(7);
		for (float[] p : PART) {
			p[0] = r.nextFloat();
			p[1] = r.nextFloat();
			p[2] = 0.012f + r.nextFloat() * 0.03f;
			p[3] = 1 + r.nextInt(2);
			p[4] = r.nextFloat() * 6.283f;
		}
	}

	private void particles(GuiGraphics g, int ac) {
		double t = System.currentTimeMillis() / 1000.0;
		for (float[] p : PART) {
			double yy = ((p[1] - t * p[2]) % 1.0 + 1.0) % 1.0;
			double xx = p[0] + Math.sin(t * 0.3 + p[4]) * 0.012;
			int x = (int) (xx * this.width), y = (int) (yy * this.height), s = (int) p[3];
			float tw = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(t * 1.5 + p[4]));
			Gfx.rect(g, x, y, x + s, y + s, Gfx.alpha(ac & 0xFFFFFF, (int) (150 * tw)));
		}
	}

	// ---------- ввод ----------

	private void dragTo(double mouseX) {
		if (dragging == null || dragW <= 0) return;
		double fr = Math.max(0, Math.min(1, (mouseX - dragX) / dragW));
		dragging.set(dragging.min + fr * (dragging.max - dragging.min));
		if (dragMod != null) Cfg.changed(dragMod);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int b = event.button();
		searchFocus = false; // если нажали на поиск - ниже его hit включит фокус обратно
		if (listening != null) {
			listening = null;
			return true;
		}
		lastClickX = event.x();
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit h = hits.get(i);
			if (!h.in(event.x(), event.y())) continue;
			Runnable r = b == 0 ? h.left() : b == 1 ? h.right() : null;
			if (r != null) {
				r.run();
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
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		scrollTarget -= (float) scrollY * 36f;
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int k = event.key();
		if (listening != null) {
			if (k == GLFW.GLFW_KEY_BACKSPACE || k == GLFW.GLFW_KEY_DELETE) {
				listening.key = 0;
				Cfg.save();
			} else if (k != GLFW.GLFW_KEY_ESCAPE) {
				listening.key = k;
				Cfg.save();
			}
			listening = null;
			return true;
		}
		if (searchFocus) {
			if (k == GLFW.GLFW_KEY_ESCAPE) {
				searchFocus = false;
				if (!query.isEmpty()) {
					query = "";
					resetScroll();
				}
			} else if (k == GLFW.GLFW_KEY_BACKSPACE) {
				if (!query.isEmpty()) {
					query = query.substring(0, query.length() - 1);
					resetScroll();
				}
			} else if (k == GLFW.GLFW_KEY_ENTER) {
				searchFocus = false;
			}
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (searchFocus) {
			int cp = event.codepoint();
			if (cp >= 32 && cp != 127 && query.length() < 24) {
				query += new String(Character.toChars(cp));
				open = null;
				resetScroll();
			}
			return true;
		}
		return super.charTyped(event);
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
