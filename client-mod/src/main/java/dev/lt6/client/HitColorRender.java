package dev.lt6.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Full-surface hit tint shared by the avatar and armour renderers.
 * Solid white texture + full brightness guarantees that original skin/armour
 * pixels and vanilla red damage overlay never bleed into the chosen colour.
 * Optional enlarged translucent emissive shells make a soft outer halo.
 */
public final class HitColorRender {
    private static final Logger LOGGER = LoggerFactory.getLogger("LT6/HitColor");
    private static final Identifier WHITE =
            Identifier.fromNamespaceAndPath("lt6", "generated/hit_color_white");
    private static boolean ready;
    private static boolean failed;

    private HitColorRender() {}

    public static boolean active(LivingEntityRenderState state) {
        if (!Cfg.HIT_COLOR.enabled || !state.hasRedOverlay
                || !(state instanceof AvatarRenderState) || state.isInvisible) return false;
        if (!ready && !failed) prepareTexture();
        return ready;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void submitSolid(SubmitNodeCollector collector, Model model,
                                   Object state, PoseStack pose, int outline) {
        int color = 0xFF000000 | Cfg.HIT_TINT.rgb();
        collector.submitModel(model, state, pose, RenderTypes.entitySolid(WHITE),
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                color, null, outline, null);

        if (!Cfg.HIT_GLOW.v || Cfg.HIT_GLOW_STRENGTH.v <= 0) return;
        double intensity = Cfg.HIT_GLOW_STRENGTH.v / 100.0;
        // Three broad, low-opacity shells glow only outside the solid model.
        for (int layer = 2; layer >= 0; layer--) {
            float scale = 1.021f + layer * 0.017f;
            int alpha = Math.min(100, (int)Math.round((20 + layer * 7) * intensity));
            if (alpha < 1) continue;
            pose.pushPose();
            pose.scale(scale, scale, scale);
            collector.submitModel(model, state, pose,
                    RenderTypes.entityTranslucentEmissive(WHITE),
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                    (alpha << 24) | (color & 0xFFFFFF), null, outline, null);
            pose.popPose();
        }
    }

    private static void prepareTexture() {
        try {
            NativeImage image = new NativeImage(4, 4, true);
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) image.setPixelABGR(x, y, 0xFFFFFFFF);
            }
            DynamicTexture texture = new DynamicTexture(() -> "LT6 Hit Color solid", image);
            Minecraft.getInstance().getTextureManager().register(WHITE, texture);
            texture.upload();
            ready = true;
        } catch (RuntimeException e) {
            failed = true;
            LOGGER.error("Could not create the white Hit Color texture; keeping vanilla renderer", e);
        }
    }
}
