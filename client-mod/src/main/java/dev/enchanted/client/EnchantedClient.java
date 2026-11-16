package dev.enchanted.client;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;

public class EnchantedClient implements ClientModInitializer {
	private static KeyMapping menuKey, zoomKey;
	private static boolean zooming;
	private static int savedFov;
	private static boolean firstTick = true;

	@Override
	public void onInitializeClient() {
		Cfg.load();

		// Правый Shift открывает меню (клавишу можно сменить в управлении), пока нет других окон
		menuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.enchanted.menu", InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_RIGHT_SHIFT, KeyMapping.Category.MISC));
		zoomKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.enchanted.zoom", InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_C, KeyMapping.Category.MISC));
		ClientTickEvents.END_CLIENT_TICK.register(EnchantedClient::tick);

		// цель Target ESP - тот, кого ударили
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			if (world.isClientSide()) {
				Hud.hit(entity);
				Overlay.hitMark();
			}
			return InteractionResult.PASS;
		});

		HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS,
				Identifier.fromNamespaceAndPath("enchanted", "target_esp"), Hud::render);
		HudElementRegistry.attachElementAfter(Identifier.fromNamespaceAndPath("enchanted", "target_esp"),
				Identifier.fromNamespaceAndPath("enchanted", "overlay"), Overlay::render);
	}

	private static void tick(Minecraft mc) {
		if (firstTick) {
			firstTick = false;
			HitColor.refresh(); // цвет удара мог примениться раньше конфига
		}
		Overlay.tick(mc);
		if (Cfg.ZOOM.enabled && mc.screen == null && zoomKey.isDown()) {
			if (!zooming) {
				savedFov = mc.options.fov().get();
				zooming = true;
			}
			mc.options.fov().set((int) Cfg.Z_FOV.v);
		} else if (zooming) {
			mc.options.fov().set(savedFov);
			zooming = false;
		}
		HitColor.tick();
		while (menuKey.consumeClick()) {
			if (mc.player != null && mc.screen == null) mc.setScreen(new EnchantedScreen());
		}
	}
}
