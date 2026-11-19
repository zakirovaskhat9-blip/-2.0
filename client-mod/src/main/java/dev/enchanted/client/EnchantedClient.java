package dev.enchanted.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.network.chat.Component;

/** Skin-only client. No ESP, HUD, ClickGUI, animations or visual cheats. */
public final class EnchantedClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Skins.init();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
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
                    }))));
    }
}
