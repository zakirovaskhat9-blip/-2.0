package dev.lt6.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.HumanoidArm;
import org.lwjgl.glfw.GLFW;

/**
 * A direct grab editor for first-person arms and held items.
 *
 * The hitboxes are projected from the actual 3D renderer, not percentages of
 * the screen. A small text tab for each rendered model is also draggable:
 * the tab's rendered and interactive rectangles are exactly identical.
 * No plus signs, crosshair overlays, invisible half-screen click targets.
 */
public final class ChatHudEditor {
    private enum Target { NONE, FPS, MAIN_HAND, OFF_HAND, MAIN_ITEM, OFF_ITEM }
    private static final Target[] ORDER = {
            Target.MAIN_HAND, Target.OFF_HAND, Target.MAIN_ITEM, Target.OFF_ITEM
    };

    private static Target dragging = Target.NONE;
    private static double downX, downY, originalX, originalY;
    private ChatHudEditor() {}

    public static void onChatOpened() { dragging = Target.NONE; }

    public static void clearAnchors() {
        dragging = Target.NONE;
        ViewmodelHitboxes.clear();
    }

    private static Minecraft mc() { return Minecraft.getInstance(); }
    private static int width() { return mc().getWindow().getGuiScaledWidth(); }
    private static int height() { return mc().getWindow().getGuiScaledHeight(); }

    private static boolean main(Target t) {
        return t == Target.MAIN_HAND || t == Target.MAIN_ITEM;
    }
    private static boolean item(Target t) {
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
        boolean isMain = main(t);
        var held = isMain ? mc().player.getMainHandItem()
                : mc().player.getOffhandItem();
        if (item(t)) return !held.isEmpty();

        if (held.isEmpty()) {
            if (isMain) return true;
            return (Cfg.HAND_VIEW.enabled && Cfg.SHOW_EMPTY_OFFHAND.v)
                    || mc().player.getMainHandItem().has(DataComponents.MAP_ID);
        }
        return held.has(DataComponents.MAP_ID)
                || (Cfg.HAND_VIEW.enabled && Cfg.SHOW_HAND_WITH_ITEM.v);
    }

    // Fallback is used only during startup/reload before the first 3D pose.
    // It locates the same visible label, never a giant invisible screen region.
    private static ViewmodelHitboxes.Rect bounds(Target t) {
        ViewmodelHitboxes.Rect exact = ViewmodelHitboxes.get(main(t), item(t));
        if (exact != null) return exact;

        double w = width(), h = height();
        boolean isItem = item(t);
        int right = mc().player != null
                && mc().player.getMainArm() == HumanoidArm.RIGHT ? 1 : -1;
        double sign = (main(t) ? 1 : -1) * right;
        double cx = w * (.5 + sign * (isItem ? .26 : .37))
                + xSetting(t).v * w * (isItem ? .115 : .17);
        double cy = h * (isItem ? .68 : .83)
                - ySetting(t).v * h * (isItem ? .13 : .17);
        double scale = Math.max(.4, Math.min(2.5, scaleSetting(t).v));
        double bw = Math.min(w * .28, w * (isItem ? .15 : .13) * scale);
        double bh = Math.min(h * .4, h * (isItem ? .2 : .18) * scale);
        return new ViewmodelHitboxes.Rect(cx-bw*.5, cy-bh*.5, bw, bh);
    }

    private static String name(Target t) {
        return switch (t) {
            case MAIN_HAND -> "Рука 1";
            case OFF_HAND -> "Рука 2";
            case MAIN_ITEM -> "Предмет 1";
            case OFF_ITEM -> "Предмет 2";
            default -> "FPS";
        };
    }

    /**
     * The label itself IS the button: same x/y/width/height for paint and hit.
     * The item label is over its mesh, the arm label is below its mesh, so
     * neither gets confused with the other even when held together.
     */
    private static ViewmodelHitboxes.Rect tab(Target t) {
        var model = bounds(t);
        double tw = mc().font.width(name(t)) + 14;
        double th = 17;
        double cx = model.cx();
        double y = item(t) ? model.y() - 5 : model.y() + model.height() - 12;
        double x = Math.max(4, Math.min(width()-tw-4, cx-tw*.5));
        y = Math.max(34, Math.min(height()-44, y));
        // Pixel snapping makes the drawn tab and clickable tab EXACTLY equal.
        return new ViewmodelHitboxes.Rect(Math.round(x), Math.round(y),
                Math.ceil(tw), Math.ceil(th));
    }

    private static Target hit(double px, double py) {
        if (mc().player == null || !(mc().screen instanceof ChatScreen))
            return Target.NONE;
        if (py >= height()-25 || py < 28) return Target.NONE;

        if (Cfg.FPS.enabled) {
            int fx = Overlay.left(width(), mc().font), fy = Overlay.top(height());
            if (px >= fx-4 && px <= fx + Overlay.panelWidth(mc().font) * Overlay.scale() + 4
                    && py >= fy-4 && py <= fy + 25 * Overlay.scale() + 4)
                return Target.FPS;
        }

        // Priority 1: exact, visible, individually labelled grab buttons.
        // They are intentionally independent of the model's mesh size.
        for (Target t : ORDER)
            if (visible(t) && tab(t).contains(px, py)) return t;

        // Priority 2: actual model bounds; Shift gives hands priority when
        // a visible hand lies underneath a held item.
        boolean shift = mc().hasShiftDown();
        for (int pass = 0; pass < 2; pass++)
            for (Target t : ORDER) {
                if (!visible(t)) continue;
                if (item(t) != (shift ? pass == 1 : pass == 0)) continue;
                if (bounds(t).contains(px, py)) return t;
            }
        return Target.NONE;
    }

    public static boolean click(MouseButtonEvent event) {
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        Target t = hit(event.x(), event.y());
        if (t == Target.NONE) return false;
        dragging = t;
        downX = event.x(); downY = event.y();
        originalX = xSetting(t).v;
        originalY = ySetting(t).v;
        owner(t).enabled = true;
        return true;
    }

    public static boolean drag(MouseButtonEvent event) {
        if (dragging == Target.NONE) return false;
        if (dragging == Target.FPS) {
            xSetting(dragging).set(originalX
                    + (event.x()-downX) * 100.0 / Math.max(1, width()));
            ySetting(dragging).set(originalY
                    + (event.y()-downY) * 100.0 / Math.max(1, height()));
        } else {
            // The moved object is chosen once at mouse-down; the other
            // object's X/Y/scale/depth values remain untouched.
            boolean isItem = item(dragging);
            double sx = isItem ? .115 : .17;
            double sy = isItem ? .13 : .17;
            xSetting(dragging).set(originalX
                    + (event.x()-downX) / Math.max(1.0, width()*sx));
            ySetting(dragging).set(originalY
                    - (event.y()-downY) / Math.max(1.0, height()*sy));
        }
        return true;
    }

    public static boolean release(MouseButtonEvent event) {
        if (dragging == Target.NONE
                || event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        Cfg.changed(owner(dragging));
        dragging = Target.NONE;
        return true;
    }

    public static boolean scroll(double px, double py, double amount) {
        if (amount == 0) return false;
        Target t = hit(px, py);
        if (t == Target.NONE) return false;
        boolean depth = mc().hasControlDown() && t != Target.FPS;
        Cfg.Slider setting = depth ? depthSetting(t) : scaleSetting(t);
        setting.set(setting.v + amount * (depth ? .05 : .1));
        owner(t).enabled = true;
        Cfg.changed(owner(t));
        return true;
    }

    public static void render(GuiGraphics g, int mouseX, int mouseY) {
        if (mc().player == null || !(mc().screen instanceof ChatScreen)) return;
        for (Target t : ORDER) {
            if (!visible(t)) continue;
            var rect = tab(t);
            int x = (int)Math.round(rect.x()), y = (int)Math.round(rect.y());
            int w = (int)Math.ceil(rect.width()), h = (int)Math.ceil(rect.height());
            boolean hovered = rect.contains(mouseX, mouseY);
            int bg = hovered ? 0xE82F5578 : 0xC1172737;
            Gfx.rr(g, x, y, w, h, 4, bg);
            Gfx.ring(g, x, y, w, h, 4, 1, hovered ? 0xFF8BDAFF : 0xFF53748F);
            Gfx.str(g, mc().font, name(t), x + 7, y + 4, 0xFFE9F6FF);
        }
    }
}
