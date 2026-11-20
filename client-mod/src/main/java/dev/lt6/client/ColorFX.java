package dev.lt6.client;

import dev.lt6.client.mixin.LT6PostEffectBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** True GPU grading; no fullscreen alpha overlays and no HUD tinting. */
public final class ColorFX {
    private static final Logger LOGGER = LoggerFactory.getLogger("LT6/ColorFX");
    private static Double initialGamma;
    private static boolean failed;
    private static Identifier ownedId;
    private ColorFX() {}

    public static void tick(Minecraft mc) {
        try {
            var gamma = mc.options.gamma();
            if (mc.player != null && Cfg.BRIGHTNESS.enabled) {
                if (initialGamma == null) initialGamma = gamma.get();
                double desired = Cfg.BRIGHTNESS_LEVEL.v;
                if (Math.abs(gamma.get() - desired) > 0.001) gamma.set(desired);
            } else if (initialGamma != null) {
                gamma.set(initialGamma);
                initialGamma = null;
            }
        } catch (RuntimeException e) {
            LOGGER.debug("Gamma adjustment unavailable", e);
        }

        if (failed) return;
        boolean wanted = mc.player != null && mc.level != null && !Cfg.SKY.enabled
                && (Cfg.FILTER.enabled || Cfg.CONTRAST.enabled);
        Identifier present = mc.gameRenderer.currentPostEffect();
        boolean weOwnIt = ownedId != null && ownedId.equals(present);
        if (!wanted) {
            if (weOwnIt) mc.gameRenderer.clearPostEffect();
            ownedId = null;
            return;
        }
        // Do not take over another mod's or spectator's post-effect.
        if (present != null && !weOwnIt) return;
        int saturation = Cfg.CONTRAST.enabled
                ? (int)Math.round((Cfg.CONTRAST_POWER.v + 100.0) / 10.0) : 10;
        int warmth = Cfg.FILTER.enabled
                ? (int)Math.round((Cfg.WARMTH.v + 100.0) / 20.0) : 5;
        saturation = Math.max(0, Math.min(20, saturation));
        warmth = Math.max(0, Math.min(10, warmth));
        Identifier wantedId = Identifier.fromNamespaceAndPath("lt6", "grading/s" + saturation + "_w" + warmth);
        if (wantedId.equals(present)) return;
        try {
            ((LT6PostEffectBridge)mc.gameRenderer).lt6$setPostEffect(wantedId);
            ownedId = wantedId;
        } catch (Throwable error) {
            failed = true;
            ownedId = null;
            LOGGER.error("GPU grading could not be enabled; disabling post-effect for this session", error);
        }
    }
}
