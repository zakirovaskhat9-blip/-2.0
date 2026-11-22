package dev.lt6.client;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Lunar-like chat HUD editor. While Minecraft chat remains open, click and drag
 * FPS or hand/item grips. Mouse wheel over a grip resizes that element;
 * Shift + wheel changes model depth. All values persist in config/lt6.json.
 */
public final class ChatHudEditor {
    private enum Target { NONE, FPS, MAIN_HAND, OFF_HAND, MAIN_ITEM, OFF_ITEM }
    private static Target drag = Target.NONE;
    private static double startMouseX, startMouseY, startX, startY;
    private static boolean dirty;
    private ChatHudEditor() { }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
            if (!(screen instanceof ChatScreen)) return;
            drag = Target.NONE;
            ScreenMouseEvents.allowMouseClick(screen).register((s, e) -> !click(e));
            ScreenMouseEvents.allowMouseDrag(screen).register((s, e, dx, dy) -> !drag(e));
            ScreenMouseEvents.allowMouseRelease(screen).register((s, e) -> !release(e));
            ScreenMouseEvents.allowMouseScroll(screen).register((s, x, y, horiz, vertical) -> !scroll(x, y, vertical));
        });
    }

    static void paintHandle(GuiGraphics g, int x, int y, String label, boolean active) {
        int c = active ? 0xFF55DBB7 : Cfg.accent();
        Gfx.rr(g, x - 9, y - 9, 18, 18, 4, 0xE0121824);
        Gfx.ring(g, x - 9, y - 9, 18, 18, 4, 1, c);
        g.fill(x - 4, y - 1, x + 5, y + 1, c);
        g.fill(x - 1, y - 4, x + 1, y + 5, c);
        var font = Minecraft.getInstance().font;
        int fw = font.width(label);
        Gfx.rr(g, x - fw / 2 - 6, y - 25, fw + 12, 13, 3, 0xC9111721);
        Gfx.str(g, font, label, x - fw / 2, y - 22, 0xFFF1F5FF);
    }

    private static int width() { return Minecraft.getInstance().getWindow().getGuiScaledWidth(); }
    private static int height() { return Minecraft.getInstance().getWindow().getGuiScaledHeight(); }
    private static int gripX(Target t) {
        boolean right = t == Target.MAIN_HAND || t == Target.MAIN_ITEM;
        Cfg.Slider pos = xSetting(t);
        int base = (int)(width() * (right ? 0.78 : 0.22));
        return base + (int)Math.round(pos.v * width() * 0.18);
    }
    private static int gripY(Target t) {
        boolean item = t == Target.MAIN_ITEM || t == Target.OFF_ITEM;
        Cfg.Slider pos = ySetting(t);
        int base = (int)(height() * (item ? 0.62 : 0.79));
        return base - (int)Math.round(pos.v * height() * 0.18);
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
    private static Target hit(double x, double y) {
        if (Cfg.FPS.enabled) {
            int fx = Overlay.left(width(), Minecraft.getInstance().font);
            int fy = Overlay.top(height());
            if (x >= fx - 4 && x <= fx + Overlay.panelWidth(Minecraft.getInstance().font) * Overlay.scale() + 4
                    && y >= fy - 4 && y <= fy + 25 * Overlay.scale() + 4) return Target.FPS;
        }
        for (Target t : new Target[]{Target.MAIN_ITEM, Target.OFF_ITEM, Target.MAIN_HAND, Target.OFF_HAND}) {
            int px = gripX(t), py = gripY(t);
            if (Math.abs(x - px) <= 22 && Math.abs(y - py) <= 22) return t;
        }
        return Target.NONE;
    }

    private static boolean click(MouseButtonEvent e) {
        if (e.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        Target t = hit(e.x(), e.y());
        if (t == Target.NONE) return false;
        drag = t;
        startMouseX = e.x();
        startMouseY = e.y();
        startX = xSetting(t).v;
        startY = ySetting(t).v;
        owner(t).enabled = true;
        dirty = true;
        return true;
    }
    private static boolean drag(MouseButtonEvent e) {
        if (drag == Target.NONE) return false;
        if (drag == Target.FPS) {
            double dx = (e.x() - startMouseX) / Math.max(1, width()) * 100.0;
            double dy = (e.y() - startMouseY) / Math.max(1, height()) * 100.0;
            xSetting(drag).set(startX + dx);
            ySetting(drag).set(startY + dy);
        } else {
            xSetting(drag).set(startX + (e.x() - startMouseX) / Math.max(1.0, width() * 0.18));
            ySetting(drag).set(startY - (e.y() - startMouseY) / Math.max(1.0, height() * 0.18));
        }
        dirty = true;
        return true;
    }
    private static boolean release(MouseButtonEvent e) {
        if (drag == Target.NONE || e.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        Cfg.changed(owner(drag));
        dirty = false;
        drag = Target.NONE;
        return true;
    }
    private static boolean scroll(double x, double y, double amount) {
        if (amount == 0) return false;
        Target t = hit(x, y);
        if (t == Target.NONE) return false;
        boolean z = Minecraft.getInstance().hasShiftDown() && t != Target.FPS;
        Cfg.Slider setting = z ? depthSetting(t) : scaleSetting(t);
        setting.set(setting.v + amount * (z ? 0.05 : 0.1));
        owner(t).enabled = true;
        Cfg.changed(owner(t));
        return true;
    }

    public static void render(GuiGraphics g, int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String title = "LT6 HUD EDIT  |  Тяни мышью  |  Колесо: размер  |  Shift+колесо: глубина";
        int tw = mc.font.width(title);
        int bx = Math.max(3, (g.guiWidth() - tw - 24) / 2);
        Gfx.rr(g, bx, 8, tw + 24, 24, 6, 0xE30C1523);
        Gfx.str(g, mc.font, title, bx + 12, 16, 0xFFE3EEFC);
        if (Cfg.FPS.enabled) {
            int fx = Overlay.left(width(), mc.font), fy = Overlay.top(height());
            int fw = Math.round(Overlay.panelWidth(mc.font) * Overlay.scale());
            int fh = Math.round(25 * Overlay.scale());
            Gfx.ring(g, fx - 3, fy - 3, fw + 6, fh + 6, 5, 1,
                    drag == Target.FPS ? 0xFF55DBB7 : Cfg.accent());
        }
        paintHandle(g, gripX(Target.MAIN_HAND), gripY(Target.MAIN_HAND),
                "Основная рука", drag == Target.MAIN_HAND);
        paintHandle(g, gripX(Target.OFF_HAND), gripY(Target.OFF_HAND),
                "Вторая рука", drag == Target.OFF_HAND);
        paintHandle(g, gripX(Target.MAIN_ITEM), gripY(Target.MAIN_ITEM),
                "Предмет справа", drag == Target.MAIN_ITEM);
        paintHandle(g, gripX(Target.OFF_ITEM), gripY(Target.OFF_ITEM),
                "Предмет слева", drag == Target.OFF_ITEM);
    }
}
