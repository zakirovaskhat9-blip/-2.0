package dev.enchanted.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

public class EnchantedClient implements ClientModInitializer {
	static final Path CFG = FabricLoader.getInstance().getConfigDir().resolve("enchanted-client.json");
	static int theme = 0;
	private static boolean wasDown = false;

	@Override
	public void onInitializeClient() {
		load();
		// Левый Shift = клавиша приседания по умолчанию: открываем меню по нажатию, когда открытых окон нет
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			boolean down = mc.options.keyShift.isDown();
			if (down && !wasDown && mc.player != null && mc.screen == null) {
				mc.setScreen(new EnchantedScreen());
			}
			wasDown = down;
		});
	}

	static void load() {
		try {
			if (Files.exists(CFG)) {
				JsonObject o = JsonParser.parseString(Files.readString(CFG)).getAsJsonObject();
				theme = Math.floorMod(o.get("theme").getAsInt(), EnchantedScreen.THEMES.length);
			}
		} catch (Exception ignored) {
		}
	}

	static void save() {
		try {
			Files.writeString(CFG, "{\"theme\":" + theme + "}");
		} catch (IOException ignored) {
		}
	}
}
