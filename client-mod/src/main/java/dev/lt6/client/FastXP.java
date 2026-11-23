package dev.lt6.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;

/** Configurable fast XP-bottle use while holding right click; capped at 10/s. */
public final class FastXP {
    private static int delay;
    private FastXP() {}

    public static void tick(Minecraft mc) {
        if (!Cfg.FAST_XP.enabled || mc.player == null || mc.level == null
                || mc.gameMode == null || mc.screen != null || mc.player.isSpectator()
                || mc.player.isHandsBusy() || !mc.options.keyUse.isDown()) {
            delay = 0;
            return;
        }

        InteractionHand hand;
        if (mc.player.getMainHandItem().is(Items.EXPERIENCE_BOTTLE))
            hand = InteractionHand.MAIN_HAND;
        else if (mc.player.getOffhandItem().is(Items.EXPERIENCE_BOTTLE))
            hand = InteractionHand.OFF_HAND;
        else {
            delay = 0;
            return;
        }

        if (delay > 0) { delay--; return; }
        delay = (int)Cfg.FAST_XP_TICKS.v - 1;
        if (mc.gameMode.useItem(mc.player, hand) instanceof InteractionResult.Success success) {
            if (success.swingSource() == InteractionResult.SwingSource.CLIENT) {
                mc.player.swing(hand);
            }
        }
    }
}
