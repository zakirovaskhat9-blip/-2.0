package dev.lt6.client;

import com.mojang.blaze3d.platform.NativeImage;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * On-client custom cape. The launcher imports and normalizes the PNG at
 * game/config/lt6-cape.png; no account authentication or remote upload.
 * Existing cape movement is provided by vanilla, and Wavey Capes may add
 * physics when installed. Never touch other players' skins.
 */
public final class CapeTextures {
    private static final Logger LOGGER = LoggerFactory.getLogger("LT6/Capes");
    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath("lt6", "generated/local_cape");
    private static final ClientAsset.ResourceTexture CAPE =
            new ClientAsset.ResourceTexture(TEXTURE, TEXTURE);
    private static long checkedAt;
    private static long lastModified = -1L;
    private static boolean ready;

    private CapeTextures() {}

    public static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        long now = System.currentTimeMillis();
        if (now - checkedAt < 800L) return;
        checkedAt = now;
        Path game = FabricLoader.getInstance().getGameDir();
        Path enabled = game.resolve("config/lt6-cape.enabled");
        Path file = game.resolve("config/lt6-cape.png");
        try {
            if (!Files.isRegularFile(enabled)
                    || !Files.readString(enabled).trim().equalsIgnoreCase("true")
                    || !Files.isRegularFile(file)) {
                ready = false;
                return;
            }
            long mod = Files.getLastModifiedTime(file).toMillis();
            if (mod == lastModified && ready) return;
            try (var input = Files.newInputStream(file)) {
                NativeImage image = NativeImage.read(input);
                int width = image.getWidth(), height = image.getHeight();
                if (width != height || width < 64 || width > 4096 || width % 64 != 0) {
                    image.close();
                    LOGGER.warn("Custom cape must be a square 64x64 UV atlas, got {}x{}: {}",
                            width, height, file);
                    ready = false;
                    return;
                }
                DynamicTexture texture = new DynamicTexture(() -> "LT6 custom cape", image);
                mc.getTextureManager().register(TEXTURE, texture);
                texture.upload();
                lastModified = mod;
                ready = true;
            }
        } catch (Exception ex) {
            ready = false;
            LOGGER.warn("Cannot load local cape texture", ex);
        }
    }

    public static PlayerSkin apply(PlayerSkin original) {
        if (!ready || original == null) return original;
        return new PlayerSkin(original.body(), CAPE, original.elytra(),
                original.model(), original.secure());
    }
}
