package dev.lt6.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.lt6.client.Cfg;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-hand matrix transforms around the actual vanilla hand/item submission.
 * Rendering continues through Mojang's normal animation and item paths, with
 * every PoseStack push protected by a finally block. No client/server packets.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class FirstPersonViewMixin {
    @Shadow
    private void renderArmWithItem(AbstractClientPlayer player, float partialTick, float pitch,
            InteractionHand hand, float swing, ItemStack item, float equip,
            PoseStack pose, SubmitNodeCollector collector, int light) {
        throw new AssertionError("Shadow of ItemInHandRenderer.renderArmWithItem was not remapped");
    }

    @Redirect(method = "renderHandsWithItems",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"),
            require = 2)
    private void lt6$viewmodelHand(ItemInHandRenderer renderer, AbstractClientPlayer player,
            float partialTick, float pitch, InteractionHand hand, float swing, ItemStack item,
            float equip, PoseStack pose, SubmitNodeCollector collector, int light) {
        boolean enabled = Cfg.HAND_VIEW.enabled;
        if (enabled) {
            boolean main = hand == InteractionHand.MAIN_HAND;
            float scale = (float)(main ? Cfg.MAIN_HAND_SIZE.v : Cfg.OFF_HAND_SIZE.v);
            double x = main ? Cfg.MAIN_HAND_X.v : Cfg.OFF_HAND_X.v;
            double y = main ? Cfg.MAIN_HAND_Y.v : Cfg.OFF_HAND_Y.v;
            double z = main ? Cfg.MAIN_HAND_Z.v : Cfg.OFF_HAND_Z.v;
            pose.pushPose();
            pose.translate(x, y, z);
            pose.scale(scale, scale, scale);
        }
        try {
            this.renderArmWithItem(player, partialTick, pitch, hand, swing, item,
                    equip, pose, collector, light);
        } finally {
            if (enabled) pose.popPose();
        }
    }

    /**
     * Independent item transform inside the normal hand matrix. Determine
     * main/off hand from player's handedness, not right/left screen side.
     * Works with left-handed players and retains empty-hand rendering.
     */
    @Inject(method = "renderItem", at = @At("HEAD"), require = 1)
    private void lt6$itemStart(LivingEntity entity, ItemStack item, ItemDisplayContext mode,
            PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        if (!Cfg.ITEM_VIEW.enabled || !isFirstPerson(mode)) return;
        boolean main = mainItem(entity, mode);
        double x = main ? Cfg.ITEM_VIEW_X.v : Cfg.ITEM_OFF_X.v;
        double y = main ? Cfg.ITEM_VIEW_Y.v : Cfg.ITEM_OFF_Y.v;
        double z = main ? Cfg.ITEM_VIEW_Z.v : Cfg.ITEM_OFF_Z.v;
        float scale = (float)(main ? Cfg.ITEM_VIEW_SIZE.v : Cfg.ITEM_OFF_SIZE.v);
        pose.pushPose();
        pose.translate(x, y, z);
        pose.scale(scale, scale, scale);
    }

    @Inject(method = "renderItem", at = @At("RETURN"), require = 1)
    private void lt6$itemEnd(LivingEntity entity, ItemStack item, ItemDisplayContext mode,
            PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        if (Cfg.ITEM_VIEW.enabled && isFirstPerson(mode)) pose.popPose();
    }

    private static boolean isFirstPerson(ItemDisplayContext mode) {
        return mode == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || mode == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
    }
    private static boolean mainItem(LivingEntity entity, ItemDisplayContext mode) {
        return (mode == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND)
                == (entity.getMainArm() == HumanoidArm.RIGHT);
    }
}
