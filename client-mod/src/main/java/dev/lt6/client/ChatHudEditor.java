package dev.lt6.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.HumanoidArm;
import org.lwjgl.glfw.GLFW;

/**
 * Direct hit regions for independent hand and item editing in Chat (T).
 * Nothing is drawn over the screen. The empty main hand is always selectable;
 * the empty offhand can be shown using the hand-view module option.
 *
 * Click/drag a visible hand or item. Wheel = scale. Shift selects the arm
 * under a held item; Ctrl + wheel = depth. Keep the chat input usable.
 */
public final class ChatHudEditor {
    private enum Target { NONE, FPS, MAIN_HAND, OFF_HAND, MAIN_ITEM, OFF_ITEM }
    private static final Target[] HANDS = {Target.MAIN_HAND, Target.OFF_HAND};
    private static final Target[] ITEMS = {Target.MAIN_ITEM, Target.OFF_ITEM};
    private static Target dragging = Target.NONE;
    private static double downX, downY, originalX, originalY;

    // Keep a grab point after releasing the mouse so every object remains
    // draggable repeatedly even after an extreme offset.
    private static final double[] lastX = new double[Target.values().length];
    private static final double[] lastY = new double[Target.values().length];
    private static final boolean[] anchored = new boolean[Target.values().length];

    private ChatHudEditor() { }

    public static void onChatOpened() { dragging = Target.NONE; }

    public static void clearAnchors() {
        dragging = Target.NONE;
        java.util.Arrays.fill(anchored, false);
    }

    private static Minecraft mc() { return Minecraft.getInstance(); }
    private static int width() { return mc().getWindow().getGuiScaledWidth(); }
    private static int height() { return mc().getWindow().getGuiScaledHeight(); }

    private static boolean isMain(Target t) {
        return t == Target.MAIN_HAND || t == Target.MAIN_ITEM;
    }
    private static boolean isItem(Target t) {
        return t == Target.MAIN_ITEM || t == Target.OFF_ITEM;
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

    private static boolean visible(Target t) {
        if (mc().player == null) return false;
        boolean main = isMain(t);
        var held = main ? mc().player.getMainHandItem() : mc().player.getOffhandItem();
        if (isItem(t)) return !held.isEmpty();
        if (held.isEmpty()) {
            if (main) return true;  // Vanilla draws the bare MAIN arm.
            return Cfg.HAND_VIEW.enabled && Cfg.SHOW_EMPTY_OFFHAND.v
                    || mc().player.getMainHandItem().has(DataComponents.MAP_ID);
        }
        return held.has(DataComponents.MAP_ID)
                || (Cfg.HAND_VIEW.enabled && Cfg.SHOW_HAND_WITH_ITEM.v);
    }

    /**
     * Independent first-person anchors. The item anchor intentionally ignores
     * hand offsets/scale: changing a hand must not drag the held item with it.
     * Handedness follows the player's main arm rather than assuming right hand.
     */
    private static Target inGroup(Target[] group, double px, double py) {
        int w = width(), h = height();
        Target best = Target.NONE;
        double nearest = Double.POSITIVE_INFINITY;
        for (Target t : group) {
            if (!visible(t)) continue;
            boolean item = isItem(t);
            boolean main = isMain(t);
            int direction = mc().player.getMainArm() == HumanoidArm.RIGHT ? 1 : -1;
            double sign = main ? direction : -direction;
            double scale = scaleSetting(t).v;
            double cx = w * (0.5 + sign * (item ? .265 : .39))
                    + xSetting(t).v * w * (item ? .115 : .17);
            double cy = h * (item ? .70 : .87)
                    - ySetting(t).v * h * (item ? .13 : .17);
            double halfX = w * (item ? .105 : .12)
                    * Math.max(.5, Math.min(2.4, scale));
            double halfY = h * (item ? .18 : .13)
                    * Math.max(.5, Math.min(2.4, scale));

            // Saved cursor anchor is used only while near the calculated region
            // or the previous release point, including after moving far away.
            if (anchored[t.ordinal()]) {
                double hx = Math.max(halfX, 23), hy = Math.max(halfY, 22);
                double d = Math.pow((px - lastX[t.ordinal()]) / hx, 2)
                        + Math.pow((py - lastY[t.ordinal()]) / hy, 2);
                if (d <= 1.0 && d - .6 < nearest) {
                    nearest = d - .6;
                    best = t;
                }
            }
            double d = Math.pow((px - cx) / Math.max(15, halfX), 2)
                    + Math.pow((py - cy) / Math.max(15, halfY), 2);
            if (d <= 1.0 && d < nearest) {
                nearest = d;
                best = t;
            }
        }
        return best;
    }

    private static Target hit(double px, double py) {
        Minecraft client = mc();
        if (client.player == null || !(client.screen instanceof ChatScreen))
            return Target.NONE;
        int w = width(), h = height();

        // Chat input, selection and chat history always take priority.
        if (py >= h - 24 || py < h * .28) return Target.NONE;

        if (Cfg.FPS.enabled) {
            int fx = Overlay.left(w, client.font), fy = Overlay.top(h);
            if (px >= fx-3 && px <= fx + Overlay.panelWidth(client.font) * Overlay.scale()+3
                    && py >= fy-3 && py <= fy + 25 * Overlay.scale()+3)
                return Target.FPS;
        }

        Target first = client.hasShiftDown() ? inGroup(HANDS, px, py) : inGroup(ITEMS, px, py);
        if (first != Target.NONE) return first;
        return client.hasShiftDown() ? inGroup(ITEMS, px, py) : inGroup(HANDS, px, py);
    }

    public static boolean click(MouseButtonEvent event) {
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        Target t = hit(event.x(), event.y());
        if (t == Target.NONE) return false;
        dragging = t;
        downX = event.x();
        downY = event.y();
        originalX = xSetting(t).v;
        originalY = ySetting(t).v;
        owner(t).enabled = true;
        lastX[t.ordinal()] = event.x();
        lastY[t.ordinal()] = event.y();
        anchored[t.ordinal()] = true;
        return true;
    }

    public static boolean drag(MouseButtonEvent event) {
        if (dragging == Target.NONE) return false;
        if (dragging == Target.FPS) {
            xSetting(dragging).set(originalX + (event.x() - downX) * 100.0 / Math.max(1, width()));
            ySetting(dragging).set(originalY + (event.y() - downY) * 100.0 / Math.max(1, height()));
        } else {
            double sx = isItem(dragging) ? .115 : .17;
            double sy = isItem(dragging) ? .13 : .17;
            xSetting(dragging).set(originalX + (event.x() - downX) / Math.max(1.0, width() * sx));
            ySetting(dragging).set(originalY - (event.y() - downY) / Math.max(1.0, height() * sy));
        }
        lastX[dragging.ordinal()] = event.x();
        lastY[dragging.ordinal()] = event.y();
        anchored[dragging.ordinal()] = true;
        return true;
    }

    public static boolean release(MouseButtonEvent event) {
        if (dragging == Target.NONE || event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT)
            return false;
        lastX[dragging.ordinal()] = event.x();
        lastY[dragging.ordinal()] = event.y();
        anchored[dragging.ordinal()] = true;
        Cfg.changed(owner(dragging));
        dragging = Target.NONE;
        return true;
    }

    public static boolean scroll(double px, double py, double amount) {
        if (amount == 0) return false;
        Target t = hit(px, py);
        if (t == Target.NONE) return false;
        // Wheel always scales the hovered visible hand/item. Shift selects a
        // hand under an item; Ctrl + wheel changes depth instead of size.
        boolean depth = mc().hasControlDown() && t != Target.FPS;
        Cfg.Slider setting = depth ? depthSetting(t) : scaleSetting(t);
        setting.set(setting.v + amount * (depth ? .05 : .1));
        owner(t).enabled = true;
        Cfg.changed(owner(t));
        return true;
    }

    // No crosses, plus signs, panels or other blocking HUD objects in chat.
    public static void render(GuiGraphics gui, int mouseX, int mouseY) { }
}
