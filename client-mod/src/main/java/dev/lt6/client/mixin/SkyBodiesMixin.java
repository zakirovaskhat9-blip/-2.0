package dev.lt6.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.lt6.client.Cfg;
import dev.lt6.client.SkyPano;
import net.minecraft.client.Minecraft;

@Mixin(targets = "net.minecraft.client.renderer.SkyRenderer")
public abstract class SkyBodiesMixin {
    @Inject(method = "renderSkyDisc", at = @At("HEAD"), cancellable = true, require = 0)
    private void lt6$sky(int color, CallbackInfo info) {
        if(Cfg.SKY.enabled && SkyPano.draw(Minecraft.getInstance())) info.cancel();
    }
    @Inject(method = "renderSunMoonAndStars", at = @At("HEAD"), cancellable = true, require = 0)
    private void lt6$hideCelestials(CallbackInfo info) {
        if(Cfg.SKY.enabled && SkyPano.available()) info.cancel();
    }
}
