package dev.lt6.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Lightweight editor: keep only the FPS HUD drag interaction.
 * Arm movement and model-position dragging are deliberately disabled.
 * Hover the actual held item and scroll to scale that item only.
 */
public final class ChatHudEditor {
    private static boolean fpsDragging;
    private static double startX, startY, startPosX, startPosY;
    private ChatHudEditor() { }

    public static void onChatOpened() { fpsDragging = false; }
    public static void clearAnchors() { fpsDragging = false; }

    private static Minecraft mc() { return Minecraft.getInstance(); }
    private static int width() { return mc().getWindow().getGuiScaledWidth(); }
    private static int height() { return mc().getWindow().getGuiScaledHeight(); }

    private static boolean fpsHit(double x, double y) {
        if (!Cfg.FPS.enabled || mc().player == null) return false;
        int fx = Overlay.left(width(), mc().font), fy = Overlay.top(height());
        return x >= fx-3 && x <= fx + Overlay.panelWidth(mc().font) * Overlay.scale()+3
                && y >= fy-3 && y <= fy + 25 * Overlay.scale()+3;
    }

    public static boolean click(MouseButtonEvent event) {
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT
                || !(mc().screen instanceof ChatScreen)
                || !fpsHit(event.x(), event.y())) return false;
        fpsDragging = true;
        startX = event.x(); startY = event.y();
        startPosX = Cfg.FPS_POS_X.v;
        startPosY = Cfg.FPS_POS_Y.v;
        return true;
    }

    public static boolean drag(MouseButtonEvent event) {
        if (!fpsDragging) return false;
        Cfg.FPS_POS_X.set(startPosX + (event.x()-startX)*100.0/Math.max(1,width()));
        Cfg.FPS_POS_Y.set(startPosY + (event.y()-startY)*100.0/Math.max(1,height()));
        return true;
    }

    public static boolean release(MouseButtonEvent event) {
        if (!fpsDragging || event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT)
            return false;
        fpsDragging = false;
        Cfg.changed(Cfg.FPS);
        return true;
    }

    public static boolean scroll(double x, double y, double amount) {
        if (amount == 0 || mc().player == null
                || !(mc().screen instanceof ChatScreen)
                || y >= height() - 25) return false;
        if (fpsHit(x, y)) {
            Cfg.FPS_SIZE.set(Cfg.FPS_SIZE.v + amount * .1);
            Cfg.changed(Cfg.FPS);
            return true;
        }

        // Item-only scaling follows the actual 3D item render bounds.
        // No hand controls, grip markers or item position changes.
        boolean[] hands = {true, false};
        for (boolean main : hands) {
            var stack = main ? mc().player.getMainHandItem() : mc().player.getOffhandItem();
            if (stack.isEmpty()) continue;
            var rect = ViewmodelHitboxes.get(main, true);
            if (rect == null || !rect.contains(x, y)) continue;
            Cfg.Slider size = main ? Cfg.ITEM_VIEW_SIZE : Cfg.ITEM_OFF_SIZE;
            size.set(size.v + amount * .1);
            Cfg.ITEM_VIEW.enabled = true;
            Cfg.changed(Cfg.ITEM_VIEW);
            return true;
        }
        return false;
    }

    public static void render(GuiGraphics graphics, int mouseX, int mouseY) {
        // No UI elements are drawn over the player's hands or items.
    }
}
