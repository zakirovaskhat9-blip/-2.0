package dev.lt6.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.world.InteractionResult;
import net.minecraft.client.Minecraft;

/** LT6 client-side visual modules. Vanilla handles player skins. */
public final class LT6Client implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        org.slf4j.LoggerFactory.getLogger("LT6").info("LT6 client initialized; GUI key defaults to Y (see Minecraft Controls > Key Binds > LT6)");
        Cfg.load();
        LT6Keys.register();
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("lt6", "hud"), (gui, dt) -> {
            Overlay.render(gui, dt);
            Hud.render(gui, dt);
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            LT6Keys.poll(mc);
            // ColorFX also resets gamma/post-effects when leaving a world.
            ColorFX.tick(mc);
            if (mc.player == null || mc.level == null) return;
            Binds.poll(mc);
            SkyOptions.tick(mc);
            SkyPacks.tick(mc);
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClientSide() && player == Minecraft.getInstance().player) {
                Hud.attack(entity);
            }
            return InteractionResult.PASS;
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            dispatcher.register(ClientCommandManager.literal("lt6").executes(cmd -> {
                LT6Keys.requestOpen();
                return 1;
            }));

        });
    }
}
