package dev.lt6.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** A clean Lunar-style FPS HUD, independently resizable from 0.5x to 4.0x. */
public final class Overlay {
    private static int frames;
    private static int fps;
    private static long since = System.currentTimeMillis();

    private Overlay() {}

    public static void render(GuiGraphics gui, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        frames++;
        long now = System.currentTimeMillis();
        if (now - since >= 750) {
            fps = (int) Math.round(frames * 1000.0 / Math.max(1L, now - since));
            frames = 0;
            since = now;
        }
        if (!Cfg.FPS.enabled) return;

        String amount = Integer.toString(fps);
        int w = Math.max(70, 33 + mc.font.width(amount));
        var pose = gui.pose();
        pose.pushMatrix();
        pose.translate(8f, 8f);
        float size = (float) Math.max(0.5, Math.min(4.0, Cfg.FPS_SIZE.v));
        pose.scale(size, size);
        Gfx.rr(gui, 0, 0, w, 24, 6, 0xD9111723);
        Gfx.ring(gui, 0, 0, w, 24, 6, 1, 0x5557B9FF);
        Gfx.rr(gui, 9, 7, 3, 10, 1, 0xFF64C2FF);
        Gfx.str(gui, mc.font, "FPS", 17, 8, 0xFF9DB4D0);
        Gfx.str(gui, mc.font, amount, w - 9 - mc.font.width(amount), 8, 0xFFFFFFFF);
        pose.popMatrix();
    }
}
