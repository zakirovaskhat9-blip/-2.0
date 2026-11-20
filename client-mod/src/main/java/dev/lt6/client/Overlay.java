package dev.lt6.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;

/** Independent FPS and latency counters in the top-left HUD. */
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
            since = now;
            frames = 0;
        }

        int y = 7;
        if (Cfg.FPS.enabled) {
            drawCounter(gui, mc, y, "LT6   FPS " + fps, Cfg.accent());
            y += 28;
        }
        if (Cfg.PING.enabled) {
            int ping = -1;
            if (mc.getConnection() != null) {
                PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
                if (info != null) ping = info.getLatency();
            }
            int color = ping < 0 ? 0xFF9EADBC
                    : ping < 100 ? 0xFF65DDA9 : ping < 200 ? 0xFFFFD16A : 0xFFFF7777;
            String label = "PING   " + (ping < 0 ? "—" : ping + " ms");
            drawCounter(gui, mc, y, label, color);
        }
    }

    private static void drawCounter(GuiGraphics gui, Minecraft mc, int y,
                                    String label, int color) {
        int width = mc.font.width(label) + 24;
        Gfx.panel(gui, 7, y, width, 22);
        Gfx.rect(gui, 11, y + 6, 14, y + 16, color);
        Gfx.str(gui, mc.font, label, 19, y + 7, 0xFFFFFFFF);
    }
}
