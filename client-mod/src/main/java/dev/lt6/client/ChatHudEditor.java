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
    // Preserve each dragged object's last screen-space location across chat
    // re-openings. The original mesh bounds are approximate; without these
    // anchors the user cannot grab an item again after moving it far away.
    private static final double[] lastX = new double[Target.values().length];
    private static final double[] lastY = new double[Target.values().length];
    private static final boolean[] hasLast = new boolean[Target.values().length];
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
     * active scales and user transforms, plus exact cursor anchors saved after
     * each successful drag. Shift prioritizes the hand under a held item.
     */
    private static Target hit(double x, double y) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.screen instanceof ChatScreen)) return Target.NONE;
        int w = width(), h = height();

        if (Cfg.FPS.enabled) {
            int fx = Overlay.left(w, mc.font), fy = Overlay.top(h);
            if (x >= fx - 3 && x <= fx + Overlay.panelWidth(mc.font) * Overlay.scale() + 3
                    && y >= fy - 3 && y <= fy + 25 * Overlay.scale() + 3) return Target.FPS;
        }

        // The chat entry box and its cursor must always remain interactive.
        if (y >= h - 24) return Target.NONE;

        boolean preferArm = mc.hasShiftDown();
        // Shift prefers the hand beneath an item. If no hand is under the
        // cursor, still allow the item to be selected for depth adjustment.
        for (int pass = 0; pass < (preferArm ? 2 : 1); pass++) {
        Target best = Target.NONE;
        double bestScore = Double.POSITIVE_INFINITY;

        for (Target t : new Target[]{Target.MAIN_ITEM, Target.OFF_ITEM, Target.MAIN_HAND, Target.OFF_HAND}) {
            boolean main = t == Target.MAIN_HAND || t == Target.MAIN_ITEM;
            boolean item = t == Target.MAIN_ITEM || t == Target.OFF_ITEM;
            if (item && (main ? mc.player.getMainHandItem() : mc.player.getOffhandItem()).isEmpty())
                continue;
            if (preferArm && ((pass == 0) == item)) continue;

            double sign = main ? 1 : -1;
            double handX = main ? Cfg.MAIN_HAND_X.v : Cfg.OFF_HAND_X.v;
            double handY = main ? Cfg.MAIN_HAND_Y.v : Cfg.OFF_HAND_Y.v;
            double handScale = main ? Cfg.MAIN_HAND_SIZE.v : Cfg.OFF_HAND_SIZE.v;
            double localX = main ? Cfg.ITEM_VIEW_X.v : Cfg.ITEM_OFF_X.v;
            double localY = main ? Cfg.ITEM_VIEW_Y.v : Cfg.ITEM_OFF_Y.v;
            double itemScale = main ? Cfg.ITEM_VIEW_SIZE.v : Cfg.ITEM_OFF_SIZE.v;

            // The parent-hand transform moves its item along with the hand;
            // the item transform is relative to that parent.
            double cx = item
                    ? w * (0.5 + sign * .265)
                            + w * (.17 * handX + .115 * localX * handScale)
                    : w * (0.5 + sign * .39) + w * .17 * handX;
            double cy = item
                    ? h * .70 - h * (.17 * handY + .13 * localY * handScale)
                    : h * .87 - h * .17 * handY;
            double radiusX = item
                    ? w * .105 * Math.max(.55, Math.min(2.2, handScale * itemScale))
                    : w * .12 * Math.max(.55, Math.min(2.0, handScale));
            double radiusY = item
                    ? h * .18 * Math.max(.55, Math.min(2.2, handScale * itemScale))
                    : h * .13 * Math.max(.55, Math.min(2.0, handScale));

            // Last drag end is a much more faithful screen-space position
            // than a synthetic perspective projection. Give it priority so
            // repeated moves and wheel resizing keep working immediately.
            if (hasLast[t.ordinal()]) {
                double lx = lastX[t.ordinal()], ly = lastY[t.ordinal()];
                double ax = Math.max(radiusX, 22), ay = Math.max(radiusY, 20);
                double anchored = Math.pow((x - lx) / ax, 2)
                                + Math.pow((y - ly) / ay, 2);
                if (anchored <= 1.0 && anchored - .6 < bestScore) {
                    best = t;
                    bestScore = anchored - .6;
                }
            }

            double dist = Math.pow((x - cx) / Math.max(radiusX, 16), 2)
                        + Math.pow((y - cy) / Math.max(radiusY, 16), 2);
            if (dist <= 1.0 && dist < bestScore) {
                best = t;
                bestScore = dist;
            }
        }
        if (best != Target.NONE) return best;
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
        lastX[selected.ordinal()] = event.x();
        lastY[selected.ordinal()] = event.y();
        hasLast[selected.ordinal()] = true;
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
        lastX[dragging.ordinal()] = event.x();
        lastY[dragging.ordinal()] = event.y();
        hasLast[dragging.ordinal()] = true;
        return true;
    }
    private static boolean release(MouseButtonEvent event) {
        if (dragging == Target.NONE || event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        // Keep the final cursor position as the next editable anchor.
        lastX[dragging.ordinal()] = event.x();
        lastY[dragging.ordinal()] = event.y();
        hasLast[dragging.ordinal()] = true;
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
