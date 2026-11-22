package dev.lt6.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.lt6.client.Cfg;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * First-person viewmodel transforms only: they never alter the actual hand,
 * held item, hitboxes or what other players see.
 *
 * The outer transform moves the individual main/off-hand render (arm + item).
 * The inner transform affects only the item mesh for independent adjustment.
 * Both stacks are balanced at all method returns.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class FirstPersonViewMixin {
    @Inject(method = "renderArmWithItem", at = @At("HEAD"), require = 1)
    private void lt6$handStart(AbstractClientPlayer player, float partialTick, float pitch,
                               InteractionHand hand, float swing, ItemStack item,
                               float equip, PoseStack pose, SubmitNodeCollector collector,
                               int light, CallbackInfo ci) {
        if (!Cfg.HAND_VIEW.enabled) return;
        boolean main = hand == InteractionHand.MAIN_HAND;
        double size = main ? Cfg.MAIN_HAND_SIZE.v : Cfg.OFF_HAND_SIZE.v;
        double x = main ? Cfg.MAIN_HAND_X.v : Cfg.OFF_HAND_X.v;
        double y = main ? Cfg.MAIN_HAND_Y.v : Cfg.OFF_HAND_Y.v;
        double z = main ? Cfg.MAIN_HAND_Z.v : Cfg.OFF_HAND_Z.v;
        pose.pushPose();
        pose.translate(x, y, z);
        pose.scale((float)size, (float)size, (float)size);
    }

    @Inject(method = "renderArmWithItem", at = @At("RETURN"), require = 1)
    private void lt6$handEnd(AbstractClientPlayer player, float partialTick, float pitch,
                             InteractionHand hand, float swing, ItemStack item,
                             float equip, PoseStack pose, SubmitNodeCollector collector,
                             int light, CallbackInfo ci) {
        if (Cfg.HAND_VIEW.enabled) pose.popPose();
    }

    @Inject(method = "renderItem", at = @At("HEAD"), require = 1)
    private void lt6$itemStart(LivingEntity entity, ItemStack item, ItemDisplayContext mode,
                               PoseStack pose, SubmitNodeCollector collector, int light,
                               CallbackInfo ci) {
        if (!Cfg.ITEM_VIEW.enabled) return;
        if (mode != ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                && mode != ItemDisplayContext.FIRST_PERSON_RIGHT_HAND) return;
        pose.pushPose();
        boolean main = mode == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
        double x = main ? Cfg.ITEM_VIEW_X.v : Cfg.ITEM_OFF_X.v;
        double y = main ? Cfg.ITEM_VIEW_Y.v : Cfg.ITEM_OFF_Y.v;
        double z = main ? Cfg.ITEM_VIEW_Z.v : Cfg.ITEM_OFF_Z.v;
        float scale = (float)(main ? Cfg.ITEM_VIEW_SIZE.v : Cfg.ITEM_OFF_SIZE.v);
        pose.translate(x, y, z);
        pose.scale(scale, scale, scale);
    }

    @Inject(method = "renderItem", at = @At("RETURN"), require = 1)
    private void lt6$itemEnd(LivingEntity entity, ItemStack item, ItemDisplayContext mode,
                             PoseStack pose, SubmitNodeCollector collector, int light,
                             CallbackInfo ci) {
        if (Cfg.ITEM_VIEW.enabled
                && (mode == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || mode == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND)) pose.popPose();
    }
}
