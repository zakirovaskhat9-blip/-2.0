package dev.lt6.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.lt6.client.HitColorRender;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The old getModelTint hook only multiplied the skin texture. Submit the whole
 * player model with a solid-white texture instead: skin and face details cannot
 * show through the uniform chosen tint. Preserve vanilla on all other entities.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class HitColorMixin {
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
            method = "submit",
            at = @At(value = "INVOKE", target =
                    "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(" +
                    "Lnet/minecraft/client/model/Model;Ljava/lang/Object;" +
                    "Lcom/mojang/blaze3d/vertex/PoseStack;" +
                    "Lnet/minecraft/client/renderer/rendertype/RenderType;III" +
                    "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;I" +
                    "Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"),
            require = 1
    )
    private void lt6$solidHit(
            SubmitNodeCollector collector, Model model, Object modelState,
            PoseStack modelPose, RenderType originalType, int light, int overlay,
            int tint, TextureAtlasSprite sprite, int outline,
            ModelFeatureRenderer.CrumblingOverlay crumbling,
            LivingEntityRenderState entityState, PoseStack pose,
            SubmitNodeCollector submitter, CameraRenderState camera) {
        if (HitColorRender.active(entityState)) {
            HitColorRender.submitSolid(collector, model, modelState, modelPose, outline);
        } else {
            collector.submitModel(model, modelState, modelPose, originalType,
                    light, overlay, tint, sprite, outline, crumbling);
        }
    }
}
