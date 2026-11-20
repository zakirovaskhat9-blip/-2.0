package dev.lt6.client.mixin;

import dev.lt6.client.Cfg;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Color the entity model during Minecraft's standard hit-flash interval. */
@Mixin(LivingEntityRenderer.class)
public abstract class HitColorMixin {
    @Inject(method = "getModelTint", at = @At("RETURN"), cancellable = true, require = 1)
    private void lt6$hitTint(LivingEntityRenderState state, CallbackInfoReturnable<Integer> cir) {
        if (Cfg.HIT_COLOR.enabled && state.hasRedOverlay) {
            cir.setReturnValue(0xFF000000 | (Cfg.HIT_TINT.rgb() & 0xFFFFFF));
        }
    }
}
