package dev.lt6.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** Registered Fabric keybind: visible in Options > Controls > Key Binds > LT6. */
public final class LT6Keys {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("lt6", "controls"));
    private static KeyMapping openGui;
    private static boolean openRequested;
    private static boolean yHeld;

    private LT6Keys() { }

    public static void register() {
        if (openGui != null) return;
        openGui = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.lt6.open_gui",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_Y,
                CATEGORY));
    }

    /** Fallback for the client-side /lt6 command. */
    public static void requestOpen() {
        openRequested = true;
    }

    public static void poll(Minecraft mc) {
        if (openGui == null) return;
        // Consume clicks even in menus so the GUI won't unexpectedly open later.
        boolean pressed = false;
        while (openGui.consumeClick()) pressed = true;
        // Guaranteed Y shortcut, even if the player's legacy options.txt
        // still remaps the Fabric keybind to Left/Right Shift.
        long window = mc.getWindow().handle();
        boolean yDown = window != 0L && GLFW.glfwGetKey(window, GLFW.GLFW_KEY_Y) == GLFW.GLFW_PRESS;
        boolean yPressed = yDown && !yHeld;
        yHeld = yDown;
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        // Y is the only keyboard shortcut, even if old options.txt remapped the bind.
        if (yPressed || openRequested) {
            openRequested = false;
            try {
                mc.setScreen(new LT6Screen());
            } catch (RuntimeException error) {
                org.slf4j.LoggerFactory.getLogger("LT6").error("Cannot open LT6 GUI", error);
                throw error;
            }
        }
    }
}
