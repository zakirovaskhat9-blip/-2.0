package dev.lt6.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;

/** Unified FPS and ping display in one compact top-left HUD panel. */
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

        // FPS and ping share one GUI panel, but can still be toggled separately.
        if (!Cfg.FPS.enabled && !Cfg.PING.enabled) return;
        String fpsText = Cfg.FPS.enabled ? "FPS " + fps : null;
        String pingText = null;
        int pingColor = 0xFF9EADBC;
        if (Cfg.PING.enabled) {
            int ping = -1;
            if (mc.getConnection() != null) {
                PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
                if (info != null) ping = info.getLatency();
            }
            pingColor = ping < 0 ? 0xFF9EADBC
                    : ping < 100 ? 0xFF65DDA9 : ping < 200 ? 0xFFFFD16A : 0xFFFF7777;
            pingText = "PING " + (ping < 0 ? "—" : ping + " ms");
        }

        final int x = 7, y = 7;
        int width = 24 + mc.font.width("LT6");
        if (fpsText != null) width += 14 + mc.font.width(fpsText);
        if (pingText != null) width += 14 + mc.font.width(pingText);
        Gfx.panel(gui, x, y, width, 22);

        int textX = x + 12;
        Gfx.str(gui, mc.font, "LT6", textX, y + 7, Cfg.accent());
        textX += mc.font.width("LT6");
        if (fpsText != null) {
            Gfx.rect(gui, textX + 7, y + 6, textX + 8, y + 16, 0x44FFFFFF);
            textX += 14;
            Gfx.str(gui, mc.font, fpsText, textX, y + 7, 0xFFFFFFFF);
            textX += mc.font.width(fpsText);
        }
        if (pingText != null) {
            Gfx.rect(gui, textX + 7, y + 6, textX + 8, y + 16, 0x44FFFFFF);
            textX += 14;
            Gfx.str(gui, mc.font, pingText, textX, y + 7, pingColor);
        }
    }
}
