package dev.lt6.client;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Рисование интерфейса: сглаженные скругления, кольца, свечение, градиенты, текст с масштабом и анимации.
 * Только GuiGraphics (fill / fillGradient / drawString), поэтому работает с любым драйвером и вместе с Sodium/Iris.
 */
public final class Gfx {
	private Gfx() {
	}

	/** Общая прозрачность (анимация появления меню). Перед рисованием HUD сбрасывается в 1. */
	static float alphaMul = 1f;

	// ---------------- анимации ----------------

	/** Плавное число: экспоненциально стремится к цели, не зависит от FPS. */
	public static final class Anim {
		float v;
		private long last;
		private boolean init;

		Anim at(float start) {
			v = start;
			init = true;
			return this;
		}

		float to(float target, float speed) {
			long n = System.nanoTime();
			float dt = last == 0 ? 0.016f : Math.min(0.1f, (n - last) / 1e9f);
			last = n;
			if (!init) {
				v = target;
				init = true;
				return v;
			}
			v += (target - v) * (1f - (float) Math.exp(-speed * dt));
			if (Math.abs(target - v) < 0.002f) v = target;
			return v;
		}
	}

	private static final Map<Object, Anim> ANIMS = new HashMap<>();

	static Anim anim(Object key) {
		return ANIMS.computeIfAbsent(key, k -> new Anim());
	}

	private static final Map<Object, Map<String, Anim>> ANIMS2 = new java.util.IdentityHashMap<>();

	/** Анимация по паре (объект, имя) без создания временных ключей. */
	static Anim anim(Object o, String s) {
		return ANIMS2.computeIfAbsent(o, k -> new HashMap<>()).computeIfAbsent(s, k -> new Anim());
	}

	static float clamp01(float v) {
		return v < 0f ? 0f : Math.min(v, 1f);
	}

	static float easeOut(float t) {
		float u = 1f - clamp01(t);
		return 1f - u * u * u;
	}

	static float easeOutBack(float t) {
		t = clamp01(t);
		float c1 = 1.70158f, c3 = c1 + 1f, u = t - 1f;
		return 1f + c3 * u * u * u + c1 * u * u;
	}

	/** Треугольная волна 0..1..0. */
	static float tri(float t) {
		t = t - (float) Math.floor(t);
		return 1f - Math.abs(2f * t - 1f);
	}

	// ---------------- цвета ----------------

	/** Применить общую прозрачность. */
	static int ga(int c) {
		return alphaMul >= 0.999f ? c : a(c, alphaMul);
	}

	/** Умножить альфу ARGB-цвета на m. */
	static int a(int c, float m) {
		int al = Math.max(0, Math.min(255, Math.round(((c >>> 24) & 255) * m)));
		return (al << 24) | (c & 0xFFFFFF);
	}

	/** RGB + альфа 0..255. */
	static int alpha(int rgb, int a255) {
		return (Math.max(0, Math.min(255, a255)) << 24) | (rgb & 0xFFFFFF);
	}

	/** Смешивание ARGB-цветов (включая альфу). */
	static int mix(int a, int b, float t) {
		t = clamp01(t);
		int al = (int) (((a >>> 24) & 255) * (1 - t) + ((b >>> 24) & 255) * t);
		int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
		return (al << 24) | (r << 16) | (g << 8) | bl;
	}

	static int acc() {
		return Cfg.accent();
	}

	static int acc2() {
		return Cfg.accent2();
	}

	/** Цвет HUD: градиент темы (основной -> второй), плавно движется; t сдвигает фазу. */
	static int hud(float t) {
		return mix(acc(), acc2(), tri((System.currentTimeMillis() % 4000L) / 4000f + t));
	}

	// ---------------- фигуры ----------------

	static void rect(GuiGraphics g, int x0, int y0, int x1, int y1, int c) {
		g.fill(x0, y0, x1, y1, ga(c));
	}

	/** Отступ края скруглённого прямоугольника в строке i угла радиуса r (дробный). */
	private static float off(int r, int i) {
		double d = r - i - 0.5;
		return (float) (r - Math.sqrt(Math.max(0.0, (double) r * r - d * d)));
	}

	/** Отступ с округлением почти целых значений: меньше полупрозрачных пикселей, больше слитых строк. */
	private static float offQ(int r, int i) {
		float o = off(r, i), fl = (float) Math.floor(o), fr = o - fl;
		return fr < 0.15f ? fl : fr > 0.85f ? fl + 1f : o;
	}

	/** Скруглённый прямоугольник со сглаженными углами. Строки с одинаковым отступом сливаются в один fill. */
	static void rr(GuiGraphics g, int x, int y, int w, int h, int r, int c) {
		if (w <= 0 || h <= 0) return;
		c = ga(c);
		if (((c >>> 24) & 255) == 0) return;
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		int i = 0;
		while (i < r) {
			float o = offQ(r, i);
			int full = (int) Math.ceil(o), fl = (int) Math.floor(o);
			if (full > fl) { // строка с полупрозрачным краем
				int pc = a(c, 1f - (o - fl));
				g.fill(x + full, y + i, x + w - full, y + i + 1, c);
				g.fill(x + full, y + h - i - 1, x + w - full, y + h - i, c);
				g.fill(x + fl, y + i, x + fl + 1, y + i + 1, pc);
				g.fill(x + w - fl - 1, y + i, x + w - fl, y + i + 1, pc);
				g.fill(x + fl, y + h - i - 1, x + fl + 1, y + h - i, pc);
				g.fill(x + w - fl - 1, y + h - i - 1, x + w - fl, y + h - i, pc);
				i++;
				continue;
			}
			int j = i + 1;
			while (j < r) {
				float o2 = offQ(r, j);
				if ((int) Math.ceil(o2) != full || (int) Math.floor(o2) != full) break;
				j++;
			}
			g.fill(x + full, y + i, x + w - full, y + j, c);
			g.fill(x + full, y + h - j, x + w - full, y + h - i, c);
			i = j;
		}
		g.fill(x, y + r, x + w, y + h - r, c);
	}

	/** Контур скруглённого прямоугольника толщиной t. */
	static void ring(GuiGraphics g, int x, int y, int w, int h, int r, int t, int c) {
		if (t < 1 || w <= 2 * t || h <= 2 * t) return;
		c = ga(c);
		if (((c >>> 24) & 255) == 0) return;
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		int ri = Math.max(0, r - t);
		g.fill(x + r, y, x + w - r, y + t, c);
		g.fill(x + r, y + h - t, x + w - r, y + h, c);
		g.fill(x, y + r, x + t, y + h - r, c);
		g.fill(x + w - t, y + r, x + w, y + h - r, c);
		int i = 0;
		while (i < r) {
			int o = Math.round(off(r, i)), in = ringIn(r, ri, t, i);
			int j = i + 1;
			while (j < r && Math.round(off(r, j)) == o && ringIn(r, ri, t, j) == in) j++;
			if (in > o) {
				g.fill(x + o, y + i, x + in, y + j, c);
				g.fill(x + w - in, y + i, x + w - o, y + j, c);
				g.fill(x + o, y + h - j, x + in, y + h - i, c);
				g.fill(x + w - in, y + h - j, x + w - o, y + h - i, c);
			}
			i = j;
		}
	}

	private static int ringIn(int r, int ri, int t, int i) {
		int in;
		if (i < t) in = r;
		else if (ri == 0) in = t;
		else in = t + Math.round(off(ri, i - t));
		return Math.min(in, r);
	}

	/** Мягкое свечение/тень: n колец по 4 прямоугольника (углы срезаны), в разы дешевле скруглённых колец. */
	static void glow(GuiGraphics g, int x, int y, int w, int h, int r, int rgb, float strength, int n) {
		n = Math.min(n, 6);
		for (int k = n; k >= 1; k--) {
			float f = 1f - (k - 1f) / n;
			int al = (int) (255 * strength * f * f * 2.2f / n);
			if (al <= 0) continue;
			int c = ga(alpha(rgb, al));
			if ((c >>> 24) == 0) continue;
			int cut = Math.min(r + k, Math.min(w, h) / 2) / 2 + 1;
			int x0 = x - k, y0 = y - k, x1 = x + w + k, y1 = y + h + k;
			g.fill(x0 + cut, y0, x1 - cut, y0 + 1, c);
			g.fill(x0 + cut, y1 - 1, x1 - cut, y1, c);
			g.fill(x0, y0 + cut, x0 + 1, y1 - cut, c);
			g.fill(x1 - 1, y0 + cut, x1, y1 - cut, c);
		}
	}

	/** Горизонтальный градиент (ступеньками, не больше 48 заливок). */
	static void hgrad(GuiGraphics g, int x, int y, int w, int h, int c0, int c1) {
		if (w <= 0 || h <= 0) return;
		int n = Math.max(1, Math.min(w, 48));
		for (int i = 0; i < n; i++) {
			int xa = x + w * i / n, xb = x + w * (i + 1) / n;
			if (xb > xa) g.fill(xa, y, xb, y + h, ga(mix(c0, c1, (i + 0.5f) / n)));
		}
	}

	/** Вертикальный градиент. */
	static void vgrad(GuiGraphics g, int x, int y, int w, int h, int c0, int c1) {
		if (w > 0 && h > 0) g.fillGradient(x, y, x + w, y + h, ga(c0), ga(c1));
	}

	// ---------------- текст ----------------

	static void str(GuiGraphics g, Font f, String s, int x, int y, int c) {
		g.drawString(f, s, x, y, ga(c));
	}

	static void strC(GuiGraphics g, Font f, String s, int cx, int y, int c) {
		g.drawString(f, s, cx - f.width(s) / 2, y, ga(c));
	}

	/** Текст с масштабом (x, y - левый верхний угол). */
	static void strS(GuiGraphics g, Font f, String s, float x, float y, int c, float scale) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		g.drawString(f, s, 0, 0, ga(c));
		g.pose().popMatrix();
	}

	/** Текст с градиентом по буквам; phase двигает градиент (анимация). */
	static void gstr(GuiGraphics g, Font f, String s, int x, int y, int c0, int c1, float phase) {
		int cx = x, n = s.length();
		for (int i = 0; i < n; i++) {
			String ch = String.valueOf(s.charAt(i));
			float t = n <= 1 ? 0f : i / (float) (n - 1);
			g.drawString(f, ch, cx, y, ga(mix(c0, c1, tri(phase + t))));
			cx += f.width(ch);
		}
	}

	static String trim(Font f, String s, int maxW) {
		if (f.width(s) <= maxW) return s;
		while (s.length() > 1 && f.width(s + "..") > maxW) s = s.substring(0, s.length() - 1);
		return s + "..";
	}

	// ---------------- готовые элементы ----------------

	static int panelBg() {
		return alpha(0x0C0C12, (int) Math.round(Cfg.UI_OPACITY.v * 2.55));
	}

	/** Стеклянная плашка HUD: тень, фон, контур и верхний блик. */
	static void panel(GuiGraphics g, int x, int y, int w, int h) {
		int r = (int) Cfg.UI_ROUND.v;
		if (Cfg.UI_GLOW.v) glow(g, x, y, w, h, r, 0x000000, 0.5f, 3);
		rr(g, x, y, w, h, r, panelBg());
		ring(g, x, y, w, h, r, 1, 0x1CFFFFFF);
		if (w > 2 * r + 2) rect(g, x + r, y + 1, x + w - r, y + 2, 0x12FFFFFF);
	}

	/** Переключатель-«пилюля» с плавным движком; t - анимация 0..1. */
	static void toggle(GuiGraphics g, int x, int y, float t, int ac) {
		rr(g, x, y, 26, 12, 6, mix(0xFF34343C, ac, t));
		int kx = x + 2 + Math.round(t * 14f);
		rr(g, kx, y + 2, 8, 8, 4, mix(0xFFEDEDED, ((ac >> 8) & 255) > 0xB0 ? 0xFF111111 : 0xFFFFFFFF, t));
	}
}
