package dev.enchanted.client.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.enchanted.client.HitColor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;

@Mixin(OverlayTexture.class)
public abstract class OverlayTextureMixin {
	@Shadow
	@Final
	private DynamicTexture texture;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void enchanted$init(CallbackInfo ci) {
		HitColor.attach(this.texture);
	}
}
