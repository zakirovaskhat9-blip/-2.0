package dev.lt6.client;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Procedural lightweight menu particles: snowfall, glowing orbs and sparks.
 * Pure GUI shapes, no file downloads, GPU textures or per-frame allocations.
 */
public final class MenuParticles {
    private MenuParticles() {}

    private static double hash(int n) {
        double d = Math.sin(n * 127.1 + 71.2) * 43758.5453123;
        return d - Math.floor(d);
    }

    public static void render(GuiGraphics g, int width, int height,
                              int panelX, int panelY, int panelW, int panelH) {
        if (!Cfg.UI_PART.v || width < 20 || height < 20) return;
        int n = Math.min(64, Math.max(4, (int)Cfg.UI_PART_DENSITY.v));
        int mode = Cfg.UI_PART_STYLE.idx;
        double time = System.nanoTime() * 1.0e-9;
        double speed = Math.max(.1, Cfg.UI_PART_SPEED.v);
        int size = Math.max(1, (int)Math.round(Cfg.UI_PART_SIZE.v));
        int a = 0xFF000000 | Cfg.UI_PART_COLOR.rgb();
        int b = 0xFF000000 | Cfg.UI_PART_COLOR_2.rgb();
        double shimmer = Math.max(0.2, Cfg.UI_PART_SHIMMER_SPEED.v);

        // Draw in the dimmed region surrounding and behind the panel so no
        // particle can steal a mouse interaction from any GUI control.
        for (int i = 0; i < n; i++) {
            double u = hash(i * 17 + 9), v = hash(i * 23 + 41);
            double drift = (hash(i * 31 + 5) - .5) * 12.0;
            double x = width * u + Math.sin(time * speed * .35 + i * .6) * (8 + 4 * u);
            double fall = (time * (6.0 + hash(i * 11 + 3) * 22.0) * speed);
            double y = ((height * v + fall) % (height + 20)) - 10;
            x = ((x + drift + width) % width);
            int px = (int)Math.round(x), py = (int)Math.round(y);

            float blend = (float)hash(i * 7 + 6);
            if (Cfg.UI_PART_SHIMMER.v)
                blend = (float)(.5 + .5 * Math.sin(time * shimmer + i * .71));
            int tint = Gfx.mix(a, b, blend);
            float twinkle = (float)(.6 + .4 * Math.sin(time * 1.2 + i));
            int c = Gfx.a(tint, Math.max(.12f, twinkle * .52f));
            int r = Math.max(1, (int)Math.round(size * (.7 + hash(i * 13 + 8) * .6)));

            if (mode == 0) {
                // Small snowflakes with a thin symmetric cross.
                g.fill(px-r-1, py, px+r+2, py+1, c);
                g.fill(px, py-r-1, px+1, py+r+2, c);
                if (r >= 2) Gfx.rr(g, px-1, py-1, 3, 3, 1, c);
            } else if (mode == 1) {
                Gfx.rr(g, px-r, py-r, r*2+1, r*2+1, r, Gfx.a(c, .70f));
                Gfx.glow(g, px-r, py-r, r*2+1, r*2+1, r, tint, .13f, 2);
            } else {
                int len = Math.max(3, r*3);
                g.fill(px-1, py-len, px+1, py+len, Gfx.a(c, .6f));
                g.fill(px-len/2, py, px+len/2+1, py+1, c);
                Gfx.rr(g, px-1, py-1, 3, 3, 1, Gfx.a(tint, .7f));
            }
        }
    }
}
