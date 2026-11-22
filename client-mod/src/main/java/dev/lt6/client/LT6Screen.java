package dev.lt6.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * LT6: a lightweight, responsive, dark Lunar-inspired menu.
 * FPS, three Target ESP styles, Custom Sky and live GPU Color Grading.
 * One click opens settings, a separate toggle turns each module on/off.
 */
public final class LT6Screen extends Screen {
    private static final int BG = 0xF0111724;
    private static final int SIDE = 0xFF101827;
    private static final int CARD = 0xFF202C3D;
    private static final int TEXT = 0xFFF2F7FF;
    private static final int MUTED = 0xFF9AACC3;
    private static final int ACCENT = 0xFF62BEFF;
    private static final String[] LABELS = {"FPS", "Target ESP", "Custom Sky", "Color Grading"};
    private static final String[] SUBS = {"Счётчик кадров", "Три прежних стиля", "Шесть наборов неба", "Цвет и яркость"};

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean inside(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private final List<Hit> hits = new ArrayList<>();
    private static int selected = 0;
    private int x, y, w, h, sideW, contentX, contentW, viewTop, viewBottom, contentHeight;
    private double scroll;
    private Cfg.Slider dragging;
    private Cfg.Module dragOwner;
    private int dragX, dragW;
    private boolean clip;

    public LT6Screen() {
        super(Component.literal("LT6 Visuals"));
    }

    @Override
    protected void init() {
        selected = Math.max(0, Math.min(3, selected));
        Cfg.SKY_PRESET.opts = SkyLib.names();
        if (Cfg.SKY_PRESET.idx >= Cfg.SKY_PRESET.opts.length) Cfg.SKY_PRESET.idx = 0;
        scroll = 0;
    }

    private Cfg.Module module() {
        return switch (selected) {
            case 0 -> Cfg.FPS;
            case 1 -> Cfg.TARGET_ESP;
            case 2 -> Cfg.SKY;
            default -> Cfg.GRADING;
        };
    }

    private void area(int ax, int ay, int aw, int ah, Runnable fn) {
        if (clip) {
            int bottom = Math.min(ay + ah, viewBottom), top = Math.max(ay, viewTop);
            if (bottom <= top) return;
            ay = top;
            ah = bottom - top;
        }
        if (aw > 0 && ah > 0) hits.add(new Hit(ax, ay, aw, ah, fn));
    }

    private static boolean hover(int mx, int my, int ax, int ay, int aw, int ah) {
        return mx >= ax && mx < ax + aw && my >= ay && my < ay + ah;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        // Never invoke the vanilla blur: only a lightweight dark scrim.
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        hits.clear();
        Gfx.alphaMul = 1f;
        this.w = Math.min(686, Math.max(260, width - 20));
        this.h = Math.min(438, Math.max(230, height - 20));
        this.x = (width - w) / 2;
        this.y = (height - h) / 2;
        this.sideW = Math.min(184, Math.max(124, w / 3));
        this.contentX = x + sideW + 20;
        this.contentW = Math.max(108, w - sideW - 38);
        this.viewTop = y + 98;
        this.viewBottom = y + h - 34;

        g.fill(0, 0, width, height, 0xAE030812);
        Gfx.glow(g, x, y, w, h, 11, 0x000000, 0.35f, 4);
        Gfx.rr(g, x, y, w, h, 11, BG);
        Gfx.rr(g, x + 1, y + 1, sideW, h - 2, 10, SIDE);
        Gfx.ring(g, x, y, w, h, 11, 1, 0x345B7DA2);
        Gfx.rr(g, x + 12, y + 17, 3, 24, 1, ACCENT);
        Gfx.str(g, font, "LT6", x + 25, y + 20, TEXT);
        Gfx.str(g, font, "VISUAL CLIENT", x + 25, y + 34, MUTED);
        Gfx.str(g, font, "МОДУЛИ", x + 17, y + 76, MUTED);

        Cfg.Module[] modules = {Cfg.FPS, Cfg.TARGET_ESP, Cfg.SKY, Cfg.GRADING};
        for (int i = 0; i < modules.length; i++) {
            final int id = i;
            Cfg.Module m = modules[i];
            int itemSpacing = Math.max(40, Math.min(61, (h - 144) / 4));
            int sy = y + 94 + i * itemSpacing, sx = x + 10, sw = sideW - 18;
            int itemHeight = Math.min(52, itemSpacing - 5);
            boolean active = selected == i;
            int fill = active ? 0xFF273F58 : hover(mx, my, sx, sy, sw, itemHeight) ? 0xFF1A2C3F : 0xFF142132;
            Gfx.rr(g, sx, sy, sw, itemHeight, 7, fill);
            if (active) Gfx.rr(g, sx, sy + 7, 3, itemHeight - 14, 1, ACCENT);
            Gfx.str(g, font, LABELS[i], sx + 11, sy + 10, active ? TEXT : 0xFFD5E3F5);
            if (itemHeight >= 42) Gfx.str(g, font, Gfx.trim(font, SUBS[i], sw - 24), sx + 11, sy + 28, MUTED);
            area(sx, sy, sw, itemHeight, () -> {
                selected = id;
                scroll = 0;
                dragging = null;
            });
            Gfx.rr(g, sx + sw - 14, sy + 11, 5, 5, 2,
                    m.enabled ? 0xFF70E1AD : 0xFF6C7888);
        }

        Gfx.str(g, font, "Y / ESC", x + 18, y + h - 22, 0xFF7F92A9);

        Cfg.Module m = module();
        Gfx.str(g, font, LABELS[selected], contentX, y + 23, TEXT);
        Gfx.str(g, font, Gfx.trim(font, m.desc, Math.max(90, contentW - 10)), contentX, y + 39, MUTED);
        Gfx.rr(g, contentX, y + 58, contentW, 32, 6, CARD);
        Gfx.str(g, font, "Включено", contentX + 12, y + 69, TEXT);
        int toggleX = contentX + contentW - 49, toggleY = y + 67;
        Gfx.toggle(g, toggleX, toggleY, m.enabled ? 1f : 0f, ACCENT);
        area(toggleX - 9, y + 58, 58, 32, () -> Cfg.toggle(m));

        int view = Math.max(1, viewBottom - viewTop);
        int maxScroll = Math.max(0, contentHeight - view);
        scroll = Math.max(0, Math.min(maxScroll, scroll));
        g.enableScissor(contentX - 2, viewTop, contentX + contentW + 2, viewBottom);
        clip = true;
        int yy = viewTop + 6 - (int) scroll;
        if (selected == 0) yy = renderFPS(g, mx, my, yy, m);
        else if (selected == 1) yy = renderESP(g, mx, my, yy, m);
        else if (selected == 2) yy = renderSky(g, mx, my, yy, m);
        else yy = renderGrading(g, mx, my, yy, m);
        contentHeight = Math.max(0, yy + (int)scroll - viewTop + 8);
        g.disableScissor();
        clip = false;

        if (contentHeight > view) {
            int trackX = contentX + contentW + 5;
            int thumb = Math.max(18, view * view / contentHeight);
            int top = viewTop + (int) ((view - thumb) * scroll / Math.max(1, contentHeight - view));
            Gfx.rr(g, trackX, viewTop, 3, view, 1, 0x365D718A);
            Gfx.rr(g, trackX, top, 3, thumb, 1, ACCENT);
        }
        Gfx.str(g, font, "Изменения сохраняются автоматически", contentX, y + h - 21, 0xFF7F92A9);
    }

    private int renderFPS(GuiGraphics g, int mx, int my, int yy, Cfg.Module m) {
        yy = slider(g, yy, Cfg.FPS_SIZE, m);
        int panelHeight = 92;
        Gfx.rr(g, contentX, yy, contentW, panelHeight, 7, CARD);
        Gfx.str(g, font, "ПРЕДПРОСМОТР", contentX + 13, yy + 12, MUTED);
        float scale = (float) Cfg.FPS_SIZE.v;
        g.pose().pushMatrix();
        g.pose().translate(contentX + 14f, yy + 36f);
        g.pose().scale(scale, scale);
        Gfx.rr(g, 0, 0, 66, 22, 5, 0xE2121D2B);
        Gfx.str(g, font, "FPS 144", 8, 7, ACCENT);
        g.pose().popMatrix();
        return yy + panelHeight + 10;
    }

    private int renderESP(GuiGraphics g, int mx, int my, int yy, Cfg.Module m) {
        Gfx.rr(g, contentX, yy, contentW, 65, 7, CARD);
        Gfx.str(g, font, "Стиль Target ESP", contentX + 13, yy + 10, TEXT);
        int gap = 5, buttonWidth = (contentW - 26 - gap * 2) / 3;
        String[] names = {"Стандарт", "Нурсултан", "Духи"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            int sx = contentX + 13 + i * (buttonWidth + gap), sy = yy + 30;
            Gfx.rr(g, sx, sy, buttonWidth, 25, 5,
                    Cfg.ESP_STYLE.idx == i ? 0xFF316494 : 0xFF172334);
            Gfx.strC(g, font, Gfx.trim(font, names[i], buttonWidth - 4),
                    sx + buttonWidth / 2, sy + 8,
                    Cfg.ESP_STYLE.idx == i ? TEXT : MUTED);
            area(sx, sy, buttonWidth, 25, () -> {
                Cfg.ESP_STYLE.idx = idx;
                Cfg.changed(m);
                scroll = 0;
            });
        }
        yy += 72;
        yy = slider(g, yy, Cfg.ESP_SIZE, m);
        if (Cfg.ESP_STYLE.idx != 2) {
            yy = slider(g, yy, Cfg.ESP_TURN_SECONDS, m);
            yy = color(g, yy, Cfg.ESP_STYLE.idx == 1 ? Cfg.ESP_DIAMOND_TINT : Cfg.ESP_TINT, m);
            yy = slider(g, yy, Cfg.ESP_STYLE.idx == 1 ? Cfg.ESP_DIAMOND_GLOW : Cfg.ESP_GLOW, m);
            yy = checkbox(g, yy, Cfg.ESP_RED_ON_HIT, m);
        } else {
            yy = slider(g, yy, Cfg.ESP_GLOW, m);
            yy = note(g, yy, "4 духа с анимированными следами вокруг цели");
        }
        return yy;
    }

    private int renderSky(GuiGraphics g, int mx, int my, int yy, Cfg.Module m) {
        yy = note(g, yy, "Наборы неба, сохранённые в твоём лаунчере");
        int gap = 8;
        int cellWidth = Math.max(54, (contentW - gap) / 2);
        for (int i = 0; i < SkyLib.names().length; i++) {
            final int idx = i;
            int sx = contentX + (i % 2) * (cellWidth + gap);
            int sy = yy + (i / 2) * 67;
            boolean active = Cfg.SKY_PRESET.idx == i;
            Gfx.rr(g, sx, sy, cellWidth, 60, 7,
                    active ? 0xFF2E4964 : CARD);
            Gfx.rr(g, sx + 9, sy + 10, Math.max(12, cellWidth - 18), 18, 4,
                    0xFF000000 | SkyLib.current(i).sky());
            Gfx.str(g, font, Gfx.trim(font, SkyLib.current(i).name, cellWidth - 16),
                    sx + 8, sy + 39, active ? TEXT : MUTED);
            if (active) Gfx.ring(g, sx, sy, cellWidth, 60, 7, 1, ACCENT);
            area(sx, sy, cellWidth, 60, () -> {
                Cfg.SKY_PRESET.idx = idx;
                Cfg.changed(m);
            });
        }
        return yy + 3 * 67 + 8;
    }


    /**
     * One-click presets plus independent manual controls, all effective on the
     * next client tick. Scrolling keeps every control reachable in small GUIs.
     */
    private int renderGrading(GuiGraphics g, int mx, int my, int yy, Cfg.Module m) {
        yy = note(g, yy, "Цветокоррекция мира через GPU");
        Gfx.str(g, font, "РЕЖИМЫ", contentX + 6, yy + 2, MUTED);
        yy += 18;
        String[] modes = Cfg.GRADING_PRESET.opts;
        int gap = 7;
        int buttonWidth = Math.max(40, (contentW - gap) / 2);
        for (int i = 0; i < modes.length; i++) {
            final int mode = i;
            int sx = contentX + (i % 2) * (buttonWidth + gap);
            int sy = yy + (i / 2) * 40;
            boolean active = Cfg.GRADING_PRESET.idx == i;
            Gfx.rr(g, sx, sy, buttonWidth, 34, 6, active ? 0xFF285C85 : CARD);
            Gfx.strC(g, font, Gfx.trim(font, modes[i], buttonWidth - 12),
                    sx + buttonWidth / 2, sy + 12, active ? TEXT : MUTED);
            if (active) Gfx.ring(g, sx, sy, buttonWidth, 34, 6, 1, ACCENT);
            area(sx, sy, buttonWidth, 34, () -> {
                Cfg.gradingPreset(mode);
                Cfg.GRADING.enabled = true;
                Cfg.changed(Cfg.GRADING);
            });
        }
        yy += ((modes.length + 1) / 2) * 40 + 14;
        Gfx.str(g, font, "РУЧНАЯ НАСТРОЙКА", contentX + 6, yy + 1, MUTED);
        yy += 19;
        yy = slider(g, yy, Cfg.GRADE_BRIGHTNESS, m);
        yy = slider(g, yy, Cfg.GRADE_CONTRAST, m);
        yy = slider(g, yy, Cfg.GRADE_WARMTH, m);
        yy = slider(g, yy, Cfg.GRADE_SATURATION, m);
        yy = slider(g, yy, Cfg.GRADE_VIBRANCE, m);
        yy = slider(g, yy, Cfg.GRADE_INTENSITY, m);
        return yy;
    }

    private int note(GuiGraphics g, int yy, String value) {
        Gfx.rr(g, contentX, yy, contentW, 40, 6, 0xFF182839);
        Gfx.str(g, font, Gfx.trim(font, value, contentW - 18),
                contentX + 10, yy + 15, MUTED);
        return yy + 48;
    }

    private int checkbox(GuiGraphics g, int yy, Cfg.Bool setting, Cfg.Module m) {
        Gfx.rr(g, contentX, yy, contentW, 38, 6, CARD);
        Gfx.str(g, font, setting.name, contentX + 11, yy + 14, TEXT);
        int sx = contentX + contentW - 39;
        Gfx.toggle(g, sx, yy + 13, setting.v ? 1f : 0f, ACCENT);
        area(contentX, yy, contentW, 38, () -> {
            setting.v = !setting.v;
            Cfg.changed(m);
        });
        return yy + 46;
    }

    private int slider(GuiGraphics g, int yy, Cfg.Slider setting, Cfg.Module m) {
        Gfx.rr(g, contentX, yy, contentW, 60, 7, CARD);
        Gfx.str(g, font, Gfx.trim(font, setting.name, contentW - 95),
                contentX + 12, yy + 10, TEXT);
        String value = String.format(Locale.ROOT, "%.1f%s", setting.v, setting.unit);
        Gfx.str(g, font, value,
                contentX + contentW - 12 - font.width(value), yy + 10, ACCENT);
        int sx = contentX + 14, sy = yy + 41, sw = Math.max(12, contentW - 28);
        double t = (setting.v - setting.min) / (setting.max - setting.min);
        int filled = Math.max(0, Math.min(sw, (int) Math.round(sw * t)));
        Gfx.rr(g, sx, sy, sw, 4, 2, 0xFF425166);
        if (filled > 1) Gfx.rr(g, sx, sy, filled, 4, 2, ACCENT);
        Gfx.rr(g, sx + filled - 5, sy - 3, 10, 10, 5, TEXT);
        area(sx - 6, sy - 12, sw + 12, 26, () -> {
            dragging = setting;
            dragOwner = m;
            dragX = sx;
            dragW = sw;
            // Actual initial position is updated from the click event.
        });
        return yy + 68;
    }

    private int color(GuiGraphics g, int yy, Cfg.Color setting, Cfg.Module m) {
        Gfx.rr(g, contentX, yy, contentW, 75, 7, CARD);
        Gfx.str(g, font, setting.name, contentX + 12, yy + 10, TEXT);
        Gfx.rr(g, contentX + contentW - 30, yy + 10, 17, 14, 3,
                0xFF000000 | setting.rgb());
        int[] values = {0xFFFFFF, 0xFF5555, 0xFFAA55, 0xE8DD55, 0x55E78C,
                0xB6F0FD, 0x4F95FF, 0xBA7CFF, 0xFF77CA};
        int count = values.length, gap = 5;
        int square = Math.max(7, Math.min(23, (contentW - 25 - (count - 1) * gap) / count));
        for (int i = 0; i < count; i++) {
            final int rgb = values[i];
            int xx = contentX + 12 + i * (square + gap);
            Gfx.rr(g, xx, yy + 38, square, 23, 4, 0xFF000000 | rgb);
            if (setting.idx != Cfg.RAINBOW && setting.rgb() == rgb)
                Gfx.ring(g, xx - 2, yy + 36, square + 4, 27, 5, 1, TEXT);
            area(xx, yy + 35, square, 30, () -> {
                setting.pick(rgb);
                Cfg.changed(m);
            });
        }
        return yy + 83;
    }

    private void updateSlider(double mouseX) {
        if (dragging == null || dragOwner == null || dragW < 1) return;
        double fraction = Math.max(0d, Math.min(1d, (mouseX - dragX) / dragW));
        dragging.set(dragging.min + fraction * (dragging.max - dragging.min));
        if (dragOwner == Cfg.GRADING) {
            Cfg.GRADING_PRESET.idx = Cfg.GRADING_PRESET.opts.length - 1; // Custom
            Cfg.GRADING.enabled = true;
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit hit = hits.get(i);
                if (!hit.inside(event.x(), event.y())) continue;
                hit.action().run();
                if (dragging != null) updateSlider(event.x());
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging != null) {
            updateSlider(event.x());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != null) {
            updateSlider(event.x());
            Cfg.changed(dragOwner);
            dragging = null;
            dragOwner = null;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (hover((int)mx, (int)my, contentX, viewTop, contentW + 8,
                Math.max(0, viewBottom - viewTop))) {
            scroll = Math.max(0, Math.min(
                    Math.max(0, contentHeight - (viewBottom - viewTop)),
                    scroll - dy * 35d));
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_Y || event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (dragging != null) Cfg.changed(dragOwner);
        Cfg.save();
        super.onClose();
    }
}
