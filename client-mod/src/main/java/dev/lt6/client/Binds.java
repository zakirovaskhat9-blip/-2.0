package dev.lt6.client;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;

/** Клавиши модулей: используем окно Minecraft вместо текущего OpenGL-контекста (пока открыт чат или меню - не срабатывают). */
final class Binds {
	private Binds() {
	}

	static void poll(Minecraft mc) {
		long win = mc.getWindow().getWindow();
		if (win == 0L) return;
		boolean free = mc.screen == null;
		for (Cfg.Module m : Cfg.ALL) {
			if (m.key <= 0) {
				m.keyDown = false;
				continue;
			}
			boolean down = GLFW.glfwGetKey(win, m.key) == GLFW.GLFW_PRESS;
			if (down && !m.keyDown && free) Cfg.toggle(m);
			m.keyDown = down;
		}
	}
}
