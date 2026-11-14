package dev.enchanted.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;

public class EnchantedClient implements ClientModInitializer {
	private static boolean wasDown = false;
	private static boolean firstTick = true;

	@Override
	public void onInitializeClient() {
		Cfg.load();

		// Левый Shift (клавиша приседания) открывает меню, пока нет других окон
		ClientTickEvents.END_CLIENT_TICK.register(EnchantedClient::tick);

		// цель Target ESP - тот, кого ударили
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			if (world.isClientSide()) Hud.hit(entity);
			return InteractionResult.PASS;
		});

		HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS,
				Identifier.fromNamespaceAndPath("enchanted", "target_esp"), Hud::render);
	}

	private static void tick(Minecraft mc) {
		if (firstTick) {
			firstTick = false;
			HitColor.refresh(); // цвет удара мог примениться раньше конфига
		}
		boolean down = mc.options.keyShift.isDown();
		if (down && !wasDown && mc.player != null && mc.screen == null) {
			mc.setScreen(new EnchantedScreen());
		}
		wasDown = down;
	}
}
