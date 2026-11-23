package dev.lt6.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.lt6.client.Cfg;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hide the vanilla sun only when LT6 Custom Sky is enabled. Keep moon/stars. */
@Mixin(SkyRenderer.class)
public abstract class LT6SkySunMixin {
    @Inject(method = "renderSun", at = @At("HEAD"), cancellable = true, require = 1)
    private void lt6$hideSunForSkyPack(float brightness, PoseStack pose, CallbackInfo ci) {
        if (Cfg.SKY.enabled) ci.cancel();
    }
}
