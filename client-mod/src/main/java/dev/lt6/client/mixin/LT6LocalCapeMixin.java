package dev.lt6.client.mixin;

import dev.lt6.client.CapeTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only the local client's cape is replaced; skin and other players remain vanilla. */
@Mixin(AbstractClientPlayer.class)
public abstract class LT6LocalCapeMixin {
    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void lt6$applyLocalCape(CallbackInfoReturnable<PlayerSkin> info) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player == (Object)this)
            info.setReturnValue(CapeTextures.apply(info.getReturnValue()));
    }
}
