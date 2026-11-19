package dev.enchanted.client;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public final class LT6Keys {
    private static boolean last;
    public static void poll(Minecraft mc) {
        long win=GLFW.glfwGetCurrentContext();
        if(win==0) return;
        boolean down=GLFW.glfwGetKey(win,GLFW.GLFW_KEY_RIGHT_SHIFT)==GLFW.GLFW_PRESS;
        if(down && !last && mc.screen==null) mc.setScreen(new LT6Screen());
        last=down;
    }
}
