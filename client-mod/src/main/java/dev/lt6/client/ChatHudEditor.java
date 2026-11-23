package dev.lt6.client;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Click the visible first-person hand/item directly while chat (T) is open.
 * The hit regions follow the user's viewmodel offsets. No on-screen handles,
 * crosses, labels, panels or click-blocking transparent overlays.
 */
public final class ChatHudEditor {
    private enum Target { NONE, FPS, MAIN_HAND, OFF_HAND, MAIN_ITEM, OFF_ITEM }

    private static Target dragging = Target.NONE;
    private static double startMouseX, startMouseY, startX, startY;
    private ChatHudEditor() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
            if (!(screen instanceof ChatScreen)) return;
            dragging = Target.NONE;
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !click(event));
            ScreenMouseEvents.allowMouseDrag(screen).register((s, event, dx, dy) -> !drag(event));
            ScreenMouseEvents.allowMouseRelease(screen).register((s, event) -> !release(event));
            ScreenMouseEvents.allowMouseScroll(screen).register((s, x, y, horiz, amount) -> !scroll(x, y, amount));
        });
    }

    private static int width() {
        return Minecraft.getInstance().getWindow().getGuiScaledWidth();
    }
    private static int height() {
        return Minecraft.getInstance().getWindow().getGuiScaledHeight();
    }

    private static Cfg.Slider xSetting(Target t) {
        return switch (t) {
            case FPS -> Cfg.FPS_POS_X;
            case MAIN_HAND -> Cfg.MAIN_HAND_X;
            case OFF_HAND -> Cfg.OFF_HAND_X;
            case MAIN_ITEM -> Cfg.ITEM_VIEW_X;
            case OFF_ITEM -> Cfg.ITEM_OFF_X;
            default -> Cfg.FPS_POS_X;
        };
    }
    private static Cfg.Slider ySetting(Target t) {
        return switch (t) {
            case FPS -> Cfg.FPS_POS_Y;
            case MAIN_HAND -> Cfg.MAIN_HAND_Y;
            case OFF_HAND -> Cfg.OFF_HAND_Y;
            case MAIN_ITEM -> Cfg.ITEM_VIEW_Y;
            case OFF_ITEM -> Cfg.ITEM_OFF_Y;
            default -> Cfg.FPS_POS_Y;
        };
    }
    private static Cfg.Slider scaleSetting(Target t) {
        return switch (t) {
            case MAIN_HAND -> Cfg.MAIN_HAND_SIZE;
            case OFF_HAND -> Cfg.OFF_HAND_SIZE;
            case MAIN_ITEM -> Cfg.ITEM_VIEW_SIZE;
            case OFF_ITEM -> Cfg.ITEM_OFF_SIZE;
            default -> Cfg.FPS_SIZE;
        };
    }
    private static Cfg.Slider depthSetting(Target t) {
        return switch (t) {
            case MAIN_HAND -> Cfg.MAIN_HAND_Z;
            case OFF_HAND -> Cfg.OFF_HAND_Z;
            case MAIN_ITEM -> Cfg.ITEM_VIEW_Z;
            case OFF_ITEM -> Cfg.ITEM_OFF_Z;
            default -> Cfg.FPS_POS_X;
        };
    }
    private static Cfg.Module owner(Target t) {
        return switch (t) {
            case MAIN_HAND, OFF_HAND -> Cfg.HAND_VIEW;
            case MAIN_ITEM, OFF_ITEM -> Cfg.ITEM_VIEW;
            default -> Cfg.FPS;
        };
    }

    private static boolean inside(double x, double y, double cx, double cy,
                                  double halfW, double halfH) {
        return x >= cx - halfW && x <= cx + halfW
                && y >= cy - halfH && y <= cy + halfH;
    }

    /**
     * First-person render meshes are submitted in 3D, not exposed as GUI
     * widgets. Approximate their clickable silhouette from screen anchors,
     * active scales and user transforms; only claim hits on occupied regions.
     * Shift prioritizes dragging the hand under a held item.
     */
    private static Target hit(double x, double y) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen == null || !(mc.screen instanceof ChatScreen))
            return Target.NONE;
        int w = width(), h = height();

        if (Cfg.FPS.enabled) {
            int fx = Overlay.left(w, mc.font), fy = Overlay.top(h);
            if (x >= fx - 3 && x <= fx + Overlay.panelWidth(mc.font) * Overlay.scale() + 3
                    && y >= fy - 3 && y <= fy + 25 * Overlay.scale() + 3)
                return Target.FPS;
        }

        // The bottom strip is the vanilla chat input; never intercept typing,
        // cursor placement, messages or links there.
        if (y >= h - 24 || y < h * 0.31) return Target.NONE;

        boolean preferArm = mc.hasShiftDown();
        for (int side = 0; side < 2; side++) {
            boolean main = side == 0;
            boolean hasItem = !(main ? mc.player.getMainHandItem() : mc.player.getOffhandItem()).isEmpty();
            double sign = main ? 1.0 : -1.0;
            Cfg.Slider armX = main ? Cfg.MAIN_HAND_X : Cfg.OFF_HAND_X;
            Cfg.Slider armY = main ? Cfg.MAIN_HAND_Y : Cfg.OFF_HAND_Y;
            Cfg.Slider armScale = main ? Cfg.MAIN_HAND_SIZE : Cfg.OFF_HAND_SIZE;
            Cfg.Slider itemX = main ? Cfg.ITEM_VIEW_X : Cfg.ITEM_OFF_X;
            Cfg.Slider itemY = main ? Cfg.ITEM_VIEW_Y : Cfg.ITEM_OFF_Y;
            Cfg.Slider itemScale = main ? Cfg.ITEM_VIEW_SIZE : Cfg.ITEM_OFF_SIZE;

            double armCx = w * (0.5 + sign * 0.39) + armX.v * w * 0.17;
            double armCy = h * 0.87 - armY.v * h * 0.17;
            double armHalfW = w * 0.12 * Math.min(1.7, armScale.v);
            double armHalfH = h * 0.13 * Math.min(1.6, armScale.v);
            boolean armHit = inside(x, y, armCx, armCy, armHalfW, armHalfH);

            double itemCx = w * (0.5 + sign * 0.265)
                    + (armX.v * 0.17 + itemX.v * 0.115) * w;
            double itemCy = h * 0.70 - (armY.v * 0.17 + itemY.v * 0.13) * h;
            double itemHalfW = w * 0.105 * Math.min(1.9, itemScale.v * armScale.v);
            double itemHalfH = h * 0.18 * Math.min(1.8, itemScale.v * armScale.v);
            boolean itemHit = hasItem && inside(x, y, itemCx, itemCy, itemHalfW, itemHalfH);

            if (preferArm && armHit) return main ? Target.MAIN_HAND : Target.OFF_HAND;
            if (itemHit) return main ? Target.MAIN_ITEM : Target.OFF_ITEM;
            if (armHit) return main ? Target.MAIN_HAND : Target.OFF_HAND;
        }
        return Target.NONE;
    }

    private static boolean click(MouseButtonEvent event) {
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        Target selected = hit(event.x(), event.y());
        if (selected == Target.NONE) return false;
        dragging = selected;
        startMouseX = event.x();
        startMouseY = event.y();
        startX = xSetting(selected).v;
        startY = ySetting(selected).v;
        owner(selected).enabled = true;
        return true;
    }
    private static boolean drag(MouseButtonEvent event) {
        if (dragging == Target.NONE) return false;
        if (dragging == Target.FPS) {
            xSetting(dragging).set(startX + (event.x() - startMouseX) * 100.0 / Math.max(1, width()));
            ySetting(dragging).set(startY + (event.y() - startMouseY) * 100.0 / Math.max(1, height()));
        } else {
            xSetting(dragging).set(startX + (event.x() - startMouseX) / Math.max(1.0, width() * 0.17));
            ySetting(dragging).set(startY - (event.y() - startMouseY) / Math.max(1.0, height() * 0.17));
        }
        return true;
    }
    private static boolean release(MouseButtonEvent event) {
        if (dragging == Target.NONE || event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        Cfg.changed(owner(dragging));
        dragging = Target.NONE;
        return true;
    }
    private static boolean scroll(double x, double y, double amount) {
        if (amount == 0) return false;
        Target target = hit(x, y);
        if (target == Target.NONE) return false;
        boolean changeDepth = Minecraft.getInstance().hasShiftDown() && target != Target.FPS;
        Cfg.Slider setting = changeDepth ? depthSetting(target) : scaleSetting(target);
        setting.set(setting.v + amount * (changeDepth ? 0.05 : 0.1));
        owner(target).enabled = true;
        Cfg.changed(owner(target));
        return true;
    }

    // Called by the existing chat mixin. Intentional: no crosses or grips.
    public static void render(GuiGraphics graphics, int mouseX, int mouseY) {
    }
}
