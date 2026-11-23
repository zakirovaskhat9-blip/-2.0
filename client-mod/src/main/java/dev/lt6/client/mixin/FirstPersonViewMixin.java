package dev.lt6.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.lt6.client.Cfg;
import dev.lt6.client.ViewmodelHitboxes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LT6 viewmodel: hands have completely vanilla size and position.
 * When holding an item, hide its first-person hand (including map hands).
 * Only the item mesh itself can be rescaled; arms are never transformed.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class FirstPersonViewMixin {

    // Vanilla renders an arm here for an empty hand and certain held items.
    // The empty arm remains vanilla; any arm that holds an item is hidden.
    @Inject(method = "renderPlayerArm", at = @At("HEAD"), cancellable = true, require = 1)
    private void lt6$hideHandWithItem(PoseStack pose, SubmitNodeCollector collector,
            int light, float equip, float swing, HumanoidArm arm, CallbackInfo ci) {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        boolean main = arm == player.getMainArm();
        ItemStack held = main ? player.getMainHandItem() : player.getOffhandItem();
        if (!held.isEmpty()) ci.cancel();
    }

    // Two-handed map rendering bypasses renderPlayerArm.
    @Inject(method = "renderMapHand", at = @At("HEAD"), cancellable = true, require = 1)
    private void lt6$hideMapHands(PoseStack pose, SubmitNodeCollector collector,
            int light, HumanoidArm arm, CallbackInfo ci) {
        ci.cancel();
    }

    private static boolean lt6$isFirstPerson(ItemDisplayContext mode) {
        return mode == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || mode == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
    }

    private static boolean lt6$isMainItem(LivingEntity entity, ItemDisplayContext mode) {
        return (mode == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND)
                == (entity.getMainArm() == HumanoidArm.RIGHT);
    }

    @Inject(method = "renderItem", at = @At("HEAD"), require = 1)
    private void lt6$itemScaleBegin(LivingEntity entity, ItemStack item,
            ItemDisplayContext mode, PoseStack pose, SubmitNodeCollector collector,
            int light, CallbackInfo ci) {
        if (item.isEmpty() || !lt6$isFirstPerson(mode)) return;
        boolean main = lt6$isMainItem(entity, mode);
        if (Cfg.ITEM_VIEW.enabled) {
            // Never translate either the arm or the held item.
            // Do not use the removed legacy X/Y/Z sliders.
            float scale = (float)(main ? Cfg.ITEM_VIEW_SIZE.v : Cfg.ITEM_OFF_SIZE.v);
            pose.pushPose();
            pose.scale(scale, scale, scale);
        }
        ViewmodelHitboxes.capture(pose, main, true);
    }

    @Inject(method = "renderItem", at = @At("RETURN"), require = 1)
    private void lt6$itemScaleEnd(LivingEntity entity, ItemStack item,
            ItemDisplayContext mode, PoseStack pose, SubmitNodeCollector collector,
            int light, CallbackInfo ci) {
        if (Cfg.ITEM_VIEW.enabled && !item.isEmpty() && lt6$isFirstPerson(mode))
            pose.popPose();
    }
}
