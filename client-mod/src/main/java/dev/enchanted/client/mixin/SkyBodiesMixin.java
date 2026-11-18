package dev.enchanted.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.enchanted.client.Cfg;

/** Custom Sky: убираем солнце и луну (облака убирает ProbeMixin). Если имя метода в игре другое - миксин просто не применится. */
@Mixin(targets = "net.minecraft.client.renderer.SkyRenderer")
public abstract class SkyBodiesMixin {
	@Inject(method = "renderSun", at = @At("HEAD"), cancellable = true, require = 0)
	private void enchanted$noSun(CallbackInfo ci) {
		if (Cfg.SKY.enabled) ci.cancel();
	}

	@Inject(method = "renderMoon", at = @At("HEAD"), cancellable = true, require = 0)
	private void enchanted$noMoon(CallbackInfo ci) {
		if (Cfg.SKY.enabled) ci.cancel();
	}
}
