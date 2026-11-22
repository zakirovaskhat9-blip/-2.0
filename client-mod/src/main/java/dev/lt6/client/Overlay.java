package dev.lt6.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;

/** Movable Lunar-style FPS HUD; coordinates are viewport percentages. */
public final class Overlay {
    private static int frames;
    private static int fps;
    private static long since = System.currentTimeMillis();
    private Overlay() { }

    static float scale() {
        return (float) Math.max(0.5, Math.min(4.0, Cfg.FPS_SIZE.v));
    }

    static int panelWidth(Font font) {
        return Math.max(76, 40 + font.width(Integer.toString(fps)));
    }

    static int left(int viewportWidth, Font font) {
        int requested = (int) Math.round(viewportWidth * Cfg.FPS_POS_X.v / 100.0);
        return Math.max(0, Math.min(Math.max(0, viewportWidth - Math.round(panelWidth(font) * scale())), requested));
    }

    static int top(int viewportHeight) {
        int requested = (int) Math.round(viewportHeight * Cfg.FPS_POS_Y.v / 100.0);
        return Math.max(0, Math.min(Math.max(0, viewportHeight - Math.round(25 * scale())), requested));
    }

    public static void render(GuiGraphics gui, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        frames++;
        long now = System.currentTimeMillis();
        if (now - since >= 750L) {
            fps = (int)Math.round(frames * 1000.0 / Math.max(1L, now - since));
            frames = 0;
            since = now;
        }
        if (!Cfg.FPS.enabled) return;
        int w = panelWidth(mc.font), x = left(gui.guiWidth(), mc.font), y = top(gui.guiHeight());
        var pose = gui.pose();
        pose.pushMatrix();
        pose.translate((float)x, (float)y);
        pose.scale(scale(), scale());
        Gfx.rr(gui, 0, 0, w, 25, 6, 0xE0101721);
        Gfx.ring(gui, 0, 0, w, 25, 6, 1, 0x667A98B4);
        Gfx.rr(gui, 9, 7, 3, 11, 1, Cfg.accent());
        Gfx.str(gui, mc.font, "FPS", 18, 8, 0xFF9EB1C9);
        String number = Integer.toString(fps);
        Gfx.str(gui, mc.font, number, w - 10 - mc.font.width(number), 8, 0xFFFFFFFF);
        pose.popMatrix();
    }
}
