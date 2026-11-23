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
 * The Visuals category contains FPS, Target ESP, Custom Sky, Color Grading
 * and both viewmodel modules. The Player category is independently navigable.
 * One click opens settings, a separate toggle turns each module on/off.
 */
public final class LT6Screen extends Screen {
    private static final int BG = 0xF20D131E;
    private static final int SIDE = 0xFF101A28;
    private static final int CARD = 0xFF1B2A3C;
    private static final int TEXT = 0xFFF2F7FF;
    private static final int MUTED = 0xFF9AACC3;
    private static final int ACCENT = 0xFF62BEFF;
    private static final String[] LABELS = {"FPS", "Target ESP", "Custom Sky", "Color Grading", "Руки", "Предметы", "Jump FX", "Hit Color"};
    private static final String[] SUBS = {"Размер и позиция", "Три прежних стиля", "Шесть наборов неба", "Цвет и яркость", "Обе руки отдельно", "Оба предмета отдельно", "Круги и искры", "Цвет при попадании"};

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean inside(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private final List<Hit> hits = new ArrayList<>();
    private static int selected = 0;
    private static int section = 0; // 0 Visuals I, 1 Visuals II, 2 Player, 3 GUI
    private static int guiPage = 0; // 0 Appearance, 1 Particles
    private int x, y, w, h, sideW, contentX, contentW, viewTop, viewBottom, contentHeight;
    private double scroll;
    private Cfg.Slider dragging;
    private Cfg.Module dragOwner;
    private int dragX, dragW;
    private boolean clip;
    private boolean movingPanel, resizingPanel;
    private double startMouseX, startMouseY, startPanelX, startPanelY;
    private int startPanelWidth, startPanelHeight, startPixelX, startPixelY;

    public LT6Screen() {
        super(Component.literal("LT6 Visuals"));
    }

    @Override
    protected void init() {
        selected = Math.max(0, Math.min(7, selected));
        section = Math.max(0, Math.min(3, section));
        Cfg.SKY_PRESET.opts = SkyLib.names();
        if (Cfg.SKY_PRESET.idx >= Cfg.SKY_PRESET.opts.length) Cfg.SKY_PRESET.idx = 0;
        scroll = 0;
    }

    private Cfg.Module module() {
        return switch (selected) {
            case 0 -> Cfg.FPS;
            case 1 -> Cfg.TARGET_ESP;
            case 2 -> Cfg.SKY;
            case 3 -> Cfg.GRADING;
            case 4 -> Cfg.HAND_VIEW;
            case 5 -> Cfg.ITEM_VIEW;
            case 6 -> Cfg.JUMP_CIRCLES;
            default -> Cfg.HIT_COLOR;
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
        // Blur is optional and rendered in the first GUI stratum below.
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        hits.clear();
        Gfx.alphaMul = 1f;
        // Native Minecraft GUI blur; no post-effect is left running after close.
        if (Cfg.UI_BLUR.v && Cfg.UI_BLUR_LEVEL.v > 0
                && minecraft != null && minecraft.level != null) {
            g.blurBeforeThisStratum();
        }
        this.w = Math.max(1, Math.min(width - 16, (int)Cfg.UI_WIDTH.v));
        this.h = Math.max(1, Math.min(height - 16, (int)Cfg.UI_HEIGHT.v));
        this.x = (int)Math.round(Math.max(0, width - w) * Cfg.UI_X.v / 100.0);
        this.y = (int)Math.round(Math.max(0, height - h) * Cfg.UI_Y.v / 100.0);
        this.sideW = Math.min(184, Math.max(124, w / 3));
        this.contentX = x + sideW + 12;
        this.contentW = Math.max(108, w - sideW - 25);
        // Use the entire compact panel: the settings pane scrolls independently.
        this.viewTop = y + 78;
        this.viewBottom = y + h - 20;

        // No full-screen dark veil. Only the small draggable panel is drawn.
        if (Cfg.UI_GLOW.v) Gfx.glow(g, x, y, w, h, 9, 0x000000, .25f, 2);
        // Constrain all decorative particles to the menu, not the entire game.
        g.enableScissor(x + 2, y + 2, x + w - 2, y + h - 2);
        MenuParticles.render(g, width, height, x, y, w, h);
        g.disableScissor();
        int opacity = Math.max(190, Math.min(250, (int)(Cfg.UI_OPACITY.v * 2.55)));
        Gfx.rr(g, x, y, w, h, 9, (opacity << 24) | (BG & 0xFFFFFF));
        Gfx.rr(g, x + 1, y + 1, sideW, h - 2, 10, SIDE);
        Gfx.ring(g, x, y, w, h, 11, 1, 0x345B7DA2);
        Gfx.rr(g, x + 9, y + 12, 3, 22, 1, ACCENT);
        Gfx.str(g, font, "LT6", x + 20, y + 12, TEXT);
        Gfx.str(g, font, "CLIENT", x + 20, y + 26, MUTED);
        // Split visuals into two actual categories instead of crowding one list.
        // All tabs always have distinct module and settings contexts.
        final String[] sections = {"V1", "V2", "Player", "GUI"};
        int tabW = Math.max(23, (sideW - 33) / sections.length), tabY = y + 47;
        for (int i = 0; i < sections.length; i++) {
            final int next = i;
            int tx = x + 10 + i * (tabW + 4);
            Gfx.rr(g, tx, tabY, tabW, 28, 5,
                    section == i ? 0xFF315579 : 0xFF182B3D);
            Gfx.strC(g, font, Gfx.trim(font, sections[i], tabW-3),
                    tx + tabW/2, tabY + 9, section == i ? TEXT : MUTED);
            area(tx, tabY, tabW, 28, () -> {
                section = next;
                if (next == 0 && selected > 3) selected = 0;
                if (next == 1 && selected < 4) selected = 4;
                scroll = 0;
                contentHeight = 0;
                dragging = null;
            });
        }

        if (section <= 1) {
            final int first = section == 0 ? 0 : 4;
            final Cfg.Module[] group = section == 0
                    ? new Cfg.Module[]{Cfg.FPS, Cfg.TARGET_ESP, Cfg.SKY, Cfg.GRADING}
                    : new Cfg.Module[]{Cfg.HAND_VIEW, Cfg.ITEM_VIEW,
                                       Cfg.JUMP_CIRCLES, Cfg.HIT_COLOR};
            for (int index = 0; index < group.length; index++) {
                final int global = first + index;
                Cfg.Module m = group[index];
                int itemSpacing = Math.max(28, Math.min(48, (h - 106) / group.length));
                int sy = y + 83 + index * itemSpacing, sx = x + 10, sw = sideW - 18;
                int itemHeight = Math.max(23, itemSpacing - 4);
                boolean active = selected == global;
                int fill = active ? 0xFF253F58
                        : hover(mx, my, sx, sy, sw, itemHeight) ? 0xFF203043
                        : 0xFF142132;
                Gfx.rr(g, sx, sy, sw, itemHeight, 6, fill);
                if (active) Gfx.rr(g, sx, sy + 4, 2, itemHeight - 8, 1, ACCENT);
                Gfx.str(g, font, Gfx.trim(font, LABELS[global], sw - 28),
                        sx + 10, sy + (itemHeight < 35 ? 9 : 8),
                        active ? TEXT : 0xFFD5E3F5);
                if (itemHeight >= 40)
                    Gfx.str(g, font, Gfx.trim(font, SUBS[global], sw - 28),
                            sx + 10, sy + 24, MUTED);
                area(sx, sy, sw, itemHeight, () -> {
                    selected = global;
                    scroll = 0;
                    contentHeight = 0;
                    dragging = null;
                });
                Gfx.rr(g, sx + sw - 13, sy + 9, 5, 5, 2,
                        m.enabled ? 0xFF70E1AD : 0xFF687D91);
            }
        } else if (section == 2) {
            // Clickable Player card: selecting it does NOT inherit Target ESP.
            Gfx.str(g, font, "PLAYER", x + 14, y + 92, MUTED);
            int sx = x + 10, sy = y + 108, sw = sideW - 18;
            Gfx.rr(g, sx, sy, sw, 51, 7, 0xFF253F58);
            Gfx.str(g, font, "Fast XP", sx + 11, sy + 11, TEXT);
            Gfx.str(g, font, "Быстрый опыт", sx + 11, sy + 30, MUTED);
            Gfx.rr(g, sx + sw - 13, sy + 10, 5, 5, 2,
                    Cfg.FAST_XP.enabled ? 0xFF70E1AD : 0xFF687D91);
            area(sx, sy, sw, 51, () -> {
                // The only Player feature can be enabled from this card too.
                Cfg.toggle(Cfg.FAST_XP);
                scroll = 0;
                contentHeight = 0;
            });
        } else {
            String[] pages = {"Оформление", "Частицы"};
            for (int i = 0; i < pages.length; i++) {
                final int id = i;
                int sy = y + 87 + i * 43, sx = x + 10, sw = sideW - 18;
                Gfx.rr(g, sx, sy, sw, 37, 6,
                        guiPage == i ? 0xFF253F58 : 0xFF182637);
                Gfx.str(g, font, pages[i], sx + 10, sy + 13,
                        guiPage == i ? TEXT : MUTED);
                area(sx, sy, sw, 37, () -> {
                    guiPage = id;
                    scroll = 0;
                    contentHeight = 0;
                    dragging = null;
                });
            }
        }

        if (h >= 270) Gfx.str(g, font, "Y / ESC  |  ДВИГАЙ ЗАГОЛОВОК", x + 12, y + h - 22, 0xFF7F92A9);

        Cfg.Module m = section == 2 ? Cfg.FAST_XP
                : section == 3 ? Cfg.STYLE : module();
        String headline = section == 2 ? "Fast XP"
                : section == 3 ? (guiPage == 0 ? "Оформление" : "Частицы")
                : LABELS[selected];
        Gfx.str(g, font, headline, contentX, y + 10, TEXT);
        Gfx.str(g, font, Gfx.trim(font,
                section == 3 ? "Минималистичный интерфейс LT6" : m.desc,
                Math.max(90, contentW - 10)), contentX, y + 25, MUTED);
        Gfx.rr(g, contentX, y + 43, contentW, 28, 5, CARD);
        if (section == 3) {
            Gfx.str(g, font, "Внешний вид", contentX + 9, y + 52, TEXT);
        } else {
            Gfx.str(g, font, "Включено", contentX + 9, y + 52, TEXT);
            int toggleX = contentX + contentW - 49;
            Gfx.toggle(g, toggleX, y + 49, m.enabled ? 1f : 0f, ACCENT);
            area(toggleX - 9, y + 43, 58, 28, () -> Cfg.toggle(m));
        }

        int view = Math.max(1, viewBottom - viewTop);
        int maxScroll = Math.max(0, contentHeight - view);
        scroll = Math.max(0, Math.min(maxScroll, scroll));
        g.enableScissor(contentX - 2, viewTop, contentX + contentW + 2, viewBottom);
        clip = true;
        int yy = viewTop + 6 - (int) scroll;
        if (section == 2) yy = renderFastXP(g, yy, m);
        else if (section == 3) yy = renderGUISettings(g, yy, m);
        else if (selected == 0) yy = renderFPS(g, mx, my, yy, m);
        else if (selected == 1) yy = renderESP(g, mx, my, yy, m);
        else if (selected == 2) yy = renderSky(g, mx, my, yy, m);
        else if (selected == 3) yy = renderGrading(g, mx, my, yy, m);
        else if (selected == 4 || selected == 5) yy = renderViewmodel(g, yy, selected == 4, m);
        else if (selected == 6) yy = renderJumpFX(g, yy, m);
        else yy = renderHitColor(g, yy, m);
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
        if (h >= 260) Gfx.str(g, font, "Автосохранение", contentX, y + h - 17, 0xFF7F92A9);
        Gfx.rr(g, x + w - 18, y + h - 18, 13, 13, 2, 0xFF31455D);
        g.fill(x + w - 14, y + h - 11, x + w - 8, y + h - 9, ACCENT);
    }

    private int renderFPS(GuiGraphics g, int mx, int my, int yy, Cfg.Module m) {
        yy = note(g, yy, "В чате (T) перетаскивай FPS мышью");
        yy = slider(g, yy, Cfg.FPS_SIZE, m);
        yy = slider(g, yy, Cfg.FPS_POS_X, m);
        yy = slider(g, yy, Cfg.FPS_POS_Y, m);
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
        if (Cfg.ESP_STYLE.idx != 2)
            yy = slider(g, yy, Cfg.ESP_TURN_SECONDS, m);
        yy = color(g, yy, Cfg.ESP_STYLE.idx == 1 ? Cfg.ESP_DIAMOND_TINT : Cfg.ESP_TINT, m);
        yy = color(g, yy, Cfg.ESP_TINT_SECONDARY, m);
        yy = choice(g, yy, Cfg.ESP_PAINT, m);
        yy = slider(g, yy, Cfg.ESP_STYLE.idx == 1 ? Cfg.ESP_DIAMOND_GLOW : Cfg.ESP_GLOW, m);
        yy = checkbox(g, yy, Cfg.ESP_RED_ON_HIT, m);
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

    /** The arm controls never change an item transform (and vice versa). */
    private int renderViewmodel(GuiGraphics g, int yy, boolean hands, Cfg.Module module) {
        yy = note(g, yy, hands
                ? "Пустая основная рука: держи слот пустым и меняй X/Y"
                : "Предмет меняется отдельно; рука остаётся на месте");
        if (hands) {
            yy = checkbox(g, yy, Cfg.SHOW_EMPTY_OFFHAND, module);
            yy = checkbox(g, yy, Cfg.SHOW_HAND_WITH_ITEM, module);
            yy = note(g, yy, "Чат (T): тяни модель; колесо — размер; Ctrl — глубина");
        }

        Gfx.str(g, font, hands ? "ОСНОВНАЯ РУКА" : "ПРЕДМЕТ В ОСНОВНОЙ", contentX + 6, yy + 3, MUTED);
        yy += 22;
        Cfg.Slider[] main = hands
                ? new Cfg.Slider[]{Cfg.MAIN_HAND_SIZE, Cfg.MAIN_HAND_X, Cfg.MAIN_HAND_Y, Cfg.MAIN_HAND_Z}
                : new Cfg.Slider[]{Cfg.ITEM_VIEW_SIZE, Cfg.ITEM_VIEW_X, Cfg.ITEM_VIEW_Y, Cfg.ITEM_VIEW_Z};
        for (Cfg.Slider setting : main) yy = slider(g, yy, setting, module);
        yy = viewmodelReset(g, yy, hands, true);

        Gfx.str(g, font, hands ? "ВТОРАЯ РУКА" : "ПРЕДМЕТ ВО ВТОРОЙ", contentX + 6, yy + 3, MUTED);
        yy += 22;
        Cfg.Slider[] off = hands
                ? new Cfg.Slider[]{Cfg.OFF_HAND_SIZE, Cfg.OFF_HAND_X, Cfg.OFF_HAND_Y, Cfg.OFF_HAND_Z}
                : new Cfg.Slider[]{Cfg.ITEM_OFF_SIZE, Cfg.ITEM_OFF_X, Cfg.ITEM_OFF_Y, Cfg.ITEM_OFF_Z};
        for (Cfg.Slider setting : off) yy = slider(g, yy, setting, module);
        yy = viewmodelReset(g, yy, hands, false);
        return yy;
    }

    private int viewmodelReset(GuiGraphics g, int yy, boolean hands, boolean main) {
        String label = main ? "Сбросить основную" : "Сбросить вторую";
        Gfx.rr(g, contentX, yy, contentW, 28, 6, CARD);
        Gfx.strC(g, font, label, contentX + contentW / 2, yy + 9, ACCENT);
        area(contentX, yy, contentW, 28, () -> {
            if (hands) Cfg.resetViewmodelHand(main);
            else Cfg.resetViewmodelItem(main);
        });
        return yy + 36;
    }

    private int renderHitColor(GuiGraphics g, int yy, Cfg.Module module) {
        yy = note(g, yy, "Однотонный игрок и броня при попадании");
        yy = color(g, yy, Cfg.HIT_TINT, module);
        yy = checkbox(g, yy, Cfg.HIT_GLOW, module);
        yy = slider(g, yy, Cfg.HIT_GLOW_STRENGTH, module);
        yy = slider(g, yy, Cfg.HIT_SIZE, module);
        return yy;
    }

    private int renderFastXP(GuiGraphics g, int yy, Cfg.Module module) {
        yy = note(g, yy, "Зажми ПКМ с пузырьками опыта (1 тик = быстрее)");
        yy = slider(g, yy, Cfg.FAST_XP_TICKS, module);
        yy = note(g, yy, "Работает локально; сервер может ограничить скорость");
        return yy;
    }

    private int renderJumpFX(GuiGraphics g, int yy, Cfg.Module module) {
        yy = note(g, yy, "Прыгай: под ногами появятся волны и искры");
        yy = choice(g, yy, Cfg.JUMP_PATTERN, module);
        yy = color(g, yy, Cfg.JUMP_TINT, module);
        yy = slider(g, yy, Cfg.JUMP_RADIUS, module);
        yy = slider(g, yy, Cfg.JUMP_DENSITY, module);
        yy = slider(g, yy, Cfg.JUMP_LIFE, module);
        return yy;
    }

    private int renderGUISettings(GuiGraphics g, int yy, Cfg.Module module) {
        if (guiPage == 0) {
            yy = note(g, yy, "Компактное окно; можно двигать за заголовок");
            yy = checkbox(g, yy, Cfg.UI_BLUR, module);
            yy = slider(g, yy, Cfg.UI_BLUR_LEVEL, module);
            yy = slider(g, yy, Cfg.UI_WIDTH, module);
            yy = slider(g, yy, Cfg.UI_HEIGHT, module);
            yy = slider(g, yy, Cfg.UI_OPACITY, module);
            yy = checkbox(g, yy, Cfg.UI_GLOW, module);
        } else {
            yy = checkbox(g, yy, Cfg.UI_PART, module);
            yy = choice(g, yy, Cfg.UI_PART_STYLE, module);
            yy = color(g, yy, Cfg.UI_PART_COLOR, module);
            yy = color(g, yy, Cfg.UI_PART_COLOR_2, module);
            yy = checkbox(g, yy, Cfg.UI_PART_SHIMMER, module);
            yy = slider(g, yy, Cfg.UI_PART_SHIMMER_SPEED, module);
            yy = slider(g, yy, Cfg.UI_PART_SPEED, module);
            yy = slider(g, yy, Cfg.UI_PART_SIZE, module);
            yy = slider(g, yy, Cfg.UI_PART_DENSITY, module);
        }
        return yy;
    }

    private int choice(GuiGraphics g, int yy, Cfg.Choice setting, Cfg.Module module) {
        int rows = (setting.opts.length + 1) / 2, gap = 6;
        int bw = Math.max(25, (contentW - gap) / 2);
        Gfx.str(g, font, setting.name, contentX + 4, yy + 2, MUTED);
        yy += 17;
        for (int i = 0; i < setting.opts.length; i++) {
            final int idx = i;
            int xx = contentX + (i % 2) * (bw + gap);
            int sy = yy + (i / 2) * 36;
            boolean active = setting.idx == i;
            Gfx.rr(g, xx, sy, bw, 30, 5, active ? 0xFF315A7D : CARD);
            Gfx.strC(g, font, Gfx.trim(font, setting.opts[i], bw-10),
                    xx + bw/2, sy + 10, active ? TEXT : MUTED);
            area(xx, sy, bw, 30, () -> {
                setting.idx = idx;
                Cfg.changed(module);
            });
        }
        return yy + rows * 36 + 12;
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
            if (m == Cfg.HAND_VIEW || m == Cfg.ITEM_VIEW) m.enabled = true;
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
        int[] values = {0x000000, 0xFFFFFF, 0xFF5555, 0xFFAA55, 0xE8DD55, 0x55E78C,
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
        // Viewmodel sliders used to update saved numbers while their
        // modules stayed disabled, so hands appeared completely broken.
        if (dragOwner == Cfg.HAND_VIEW || dragOwner == Cfg.ITEM_VIEW
                || dragOwner == Cfg.FAST_XP) dragOwner.enabled = true;
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
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            startMouseX = event.x();
            startMouseY = event.y();
            startPanelX = Cfg.UI_X.v;
            startPanelY = Cfg.UI_Y.v;
            startPanelWidth = w;
            startPanelHeight = h;
            startPixelX = x;
            startPixelY = y;
            if (hover((int)event.x(), (int)event.y(), x + w - 24, y + h - 24, 24, 24)) {
                resizingPanel = true;
                return true;
            }
            if (hover((int)event.x(), (int)event.y(), x, y, w, 55)) {
                movingPanel = true;
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
        if (movingPanel) {
            Cfg.UI_X.set(startPanelX + (event.x() - startMouseX) * 100.0 / Math.max(1, width - w));
            Cfg.UI_Y.set(startPanelY + (event.y() - startMouseY) * 100.0 / Math.max(1, height - h));
            return true;
        }
        if (resizingPanel) {
            Cfg.UI_WIDTH.set(startPanelWidth + event.x() - startMouseX);
            Cfg.UI_HEIGHT.set(startPanelHeight + event.y() - startMouseY);
            int newWidth = Math.min(width - 16, (int)Cfg.UI_WIDTH.v);
            int newHeight = Math.min(height - 16, (int)Cfg.UI_HEIGHT.v);
            Cfg.UI_X.set(startPixelX * 100.0 / Math.max(1, width - newWidth));
            Cfg.UI_Y.set(startPixelY * 100.0 / Math.max(1, height - newHeight));
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
        if (movingPanel || resizingPanel) {
            movingPanel = false;
            resizingPanel = false;
            Cfg.save();
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
        movingPanel = false;
        resizingPanel = false;
        Cfg.save();
        super.onClose();
    }
}
