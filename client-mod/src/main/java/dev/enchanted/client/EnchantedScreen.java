package dev.enchanted.client;

import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class EnchantedScreen extends Screen {
	static final String[] THEMES = {"Изумруд", "Океан", "Аметист", "Рубин", "Янтарь"};
	static final int[] ACCENT = {0xFF7CFFB2, 0xFF6AB7FF, 0xFFB48CFF, 0xFFFF6B6B, 0xFFFFB04D};
	static final int[] PANEL = {0xF00B1A15, 0xF00B1420, 0xF0140F1F, 0xF01F0E10, 0xF01F160B};
	private static final int PW = 300, PH = 214;
	private static int tab = 0; // 0 = Visuals, 1 = Render

	private int px, py, row;

	public EnchantedScreen() {
		super(Component.literal("Enchanted"));
	}

	@Override
	protected void init() {
		px = (this.width - PW) / 2;
		py = (this.height - PH) / 2;
		row = 0;
		addRenderableWidget(Button.builder(Component.literal("Visuals"), b -> {
			tab = 0;
			rebuildWidgets();
		}).bounds(px + 10, py + 28, 140, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Render"), b -> {
			tab = 1;
			rebuildWidgets();
		}).bounds(px + 150, py + 28, 140, 20).build());

		Options o = Minecraft.getInstance().options;
		if (tab == 0) {
			add(() -> "FOV: " + o.fov().get(), () -> o.fov().set(next(o.fov().get(), 70, 80, 90, 100, 110)));
			add(() -> "Яркость: " + bright(o.gamma().get()), () -> {
				double g = o.gamma().get();
				o.gamma().set(g < 0.25 ? 0.5 : (g < 0.75 ? 1.0 : 0.0));
			});
			add(() -> "Покачивание камеры: " + onOff(o.bobView().get()), () -> o.bobView().set(!o.bobView().get()));
		} else {
			add(() -> "Дальность прорисовки: " + o.renderDistance().get(),
					() -> o.renderDistance().set(next(o.renderDistance().get(), 8, 12, 16, 24, 32)));
			add(() -> "Макс. FPS: " + (o.framerateLimit().get() >= 260 ? "без лимита" : o.framerateLimit().get()),
					() -> o.framerateLimit().set(next(o.framerateLimit().get(), 60, 120, 165, 240, 260)));
			add(() -> "VSync: " + onOff(o.enableVsync().get()), () -> o.enableVsync().set(!o.enableVsync().get()));
		}

		addRenderableWidget(Button.builder(Component.literal("Тема: " + THEMES[EnchantedClient.theme]), b -> {
			EnchantedClient.theme = (EnchantedClient.theme + 1) % THEMES.length;
			EnchantedClient.save();
			rebuildWidgets();
		}).bounds(px + 10, py + PH - 52, 280, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Закрыть"), b -> onClose())
				.bounds(px + 10, py + PH - 28, 280, 20).build());
	}

	private void add(Supplier<String> label, Runnable action) {
		int y = py + 60 + row * 24;
		row++;
		addRenderableWidget(Button.builder(Component.literal(label.get()), b -> {
			action.run();
			b.setMessage(Component.literal(label.get()));
		}).bounds(px + 10, y, 280, 20).build());
	}

	private static int next(int cur, int... vals) {
		for (int v : vals) {
			if (v > cur) {
				return v;
			}
		}
		return vals[0];
	}

	private static String onOff(boolean b) {
		return b ? "вкл" : "выкл";
	}

	private static String bright(double g) {
		return g < 0.25 ? "тусклая" : (g < 0.75 ? "обычная" : "яркая");
	}

	// фон рисуем сами в render(), поэтому стандартное размытие отключено
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		int a = ACCENT[EnchantedClient.theme];
		g.fill(0, 0, this.width, this.height, 0x66000000);
		g.fill(px - 1, py - 1, px + PW + 1, py + PH + 1, a);
		g.fill(px, py, px + PW, py + PH, PANEL[EnchantedClient.theme]);
		g.fill(px + 10 + tab * 140, py + 50, px + 150 + tab * 140, py + 52, a);
		super.render(g, mouseX, mouseY, delta);
		g.drawString(this.font, "ENCHANTED", px + 10, py + 10, a);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().options.save();
		super.onClose();
	}
}
