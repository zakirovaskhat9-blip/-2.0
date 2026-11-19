package dev.lt6.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** LT6 performance overlay. */
public final class Overlay {
    private static int frames, fps;
    private static long since = System.currentTimeMillis();
    private Overlay() {}
    public static void render(GuiGraphics gui, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        frames++;
        long now = System.currentTimeMillis();
        if (now - since >= 750) {
            fps = (int) Math.round(frames * 1000.0 / Math.max(1, now - since));
            since = now; frames = 0;
        }
        if (!Cfg.FPS.enabled) return;
        String label = "LT6   FPS " + fps;
        int width = mc.font.width(label) + 22;
        Gfx.panel(gui, 7, 7, width, 22);
        Gfx.rect(gui, 11, 13, 13, 23, Cfg.accent());
        Gfx.str(gui, mc.font, label, 19, 14, 0xFFFFFFFF);
    }
}
