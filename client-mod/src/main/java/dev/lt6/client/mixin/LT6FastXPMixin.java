package dev.lt6.client.mixin;

import dev.lt6.client.Cfg;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Reuse Minecraft's actual right-click processing instead of sending additional
 * unsynchronized item packets from a client-tick event. Vanilla sets a four-
 * tick use-item cooldown; only experience bottles receive a configurable
 * shorter cooldown (one tick by default). The server remains authoritative.
 */
@Mixin(Minecraft.class)
public abstract class LT6FastXPMixin {
    @ModifyConstant(method = "startUseItem",
            constant = @Constant(intValue = 4), require = 1)
    private int lt6$fastBottleUse(int vanillaCooldown) {
        Minecraft mc = (Minecraft)(Object)this;
        if (!Cfg.FAST_XP.enabled || mc.player == null || mc.screen != null
                || mc.gameMode == null || mc.player.isSpectator()) return vanillaCooldown;
        if (!mc.player.getMainHandItem().is(Items.EXPERIENCE_BOTTLE)
                && !mc.player.getOffhandItem().is(Items.EXPERIENCE_BOTTLE))
            return vanillaCooldown;
        return Math.max(1, Math.min(vanillaCooldown, (int)Cfg.FAST_XP_TICKS.v));
    }
}
