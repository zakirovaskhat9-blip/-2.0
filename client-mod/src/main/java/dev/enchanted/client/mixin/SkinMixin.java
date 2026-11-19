package dev.enchanted.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.enchanted.client.Skins;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;

/** Скины Enchanted: подменяем скин игрока, если он есть на сервере скинов (или это твой локальный скин). */
@Mixin(PlayerInfo.class)
public abstract class SkinMixin {
	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true, require = 0)
	private void enchanted$skin(CallbackInfoReturnable<PlayerSkin> cir) {
		if (!Skins.active) return;
		PlayerSkin orig = cir.getReturnValue();
		PlayerSkin res = Skins.override(((PlayerInfo) (Object) this).getProfile().name(), orig);
		if (res != orig) cir.setReturnValue(res);
	}
}
