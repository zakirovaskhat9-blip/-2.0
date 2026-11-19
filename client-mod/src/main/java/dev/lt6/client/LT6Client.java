package dev.lt6.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.world.InteractionResult;
import net.minecraft.client.Minecraft;

/** LT6 visuals and skin integration. */
public final class LT6Client implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        org.slf4j.LoggerFactory.getLogger("LT6").info("LT6 client initialized; GUI key defaults to Right Shift (see Minecraft Controls > Key Binds > LT6)");
        Skins.init();
        Cfg.load();
        LT6Keys.register();
        HudElementRegistry.addFirst(Identifier.fromNamespaceAndPath("lt6", "color_grading"),
            (gui, dt) -> ColorFX.render(gui));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("lt6", "hud"), (gui, dt) -> {
            Overlay.render(gui, dt);
            Hud.render(gui, dt);
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            LT6Keys.poll(mc);
            if (mc.player == null || mc.level == null) return;
            Binds.poll(mc);
            JumpFX.tick(mc);
            ColorFX.tick(mc);
            SkyOptions.tick(mc);
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClientSide() && player == Minecraft.getInstance().player) {
                Hud.hit(entity);
                HitFX.hit(entity);
            }
            return InteractionResult.PASS;
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            dispatcher.register(ClientCommandManager.literal("lt6").executes(cmd -> {
                LT6Keys.requestOpen();
                return 1;
            }));
            dispatcher.register(
            ClientCommandManager.literal("eskin")
                .executes(cmd -> { cmd.getSource().sendFeedback(Component.literal("Скин: " + Skins.current() + ". /eskin <ник> или /eskin reset")); return 1; })
                .then(ClientCommandManager.argument("nick", StringArgumentType.word())
                    .executes(cmd -> {
                        String nick = StringArgumentType.getString(cmd, "nick");
                        if (nick.equalsIgnoreCase("reset")) {
                            Skins.reset();
                            cmd.getSource().sendFeedback(Component.literal("Скин сброшен."));
                        } else if (Skins.select(nick)) {
                            cmd.getSource().sendFeedback(Component.literal("Скин " + nick + " выбран. Загрузка начнётся автоматически."));
                        } else {
                            cmd.getSource().sendFeedback(Component.literal("Ник должен содержать 3–16 символов: A-Z, 0-9, _."));
                        }
                        return 1;
                    })));
        });
    }
}
