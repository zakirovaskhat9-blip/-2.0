package dev.lt6.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.lt6.client.Cfg;
import dev.lt6.client.ViewmodelHitboxes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LT6 viewmodel v2 — separate transforms at the actual vanilla draw methods.
 *
 * Old code wrapped renderArmWithItem, which contains both the arm and held
 * item, so moving/scaling a hand always moved/scaled the item as well.
 * This mixin changes ONLY renderPlayerArm/renderMapHand for the arm and ONLY
 * renderItem for an item. The surrounding vanilla matrices are never touched.
 *
 * Empty main hand uses vanilla renderPlayerArm. An optional empty offhand is
 * drawn by calling that same method, without changing the held item at all.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class FirstPersonViewMixin {
    @Shadow @Final private Minecraft minecraft;

    @Shadow
    private void renderPlayerArm(PoseStack pose, SubmitNodeCollector collector,
            int light, float equip, float swing, HumanoidArm arm) {
        throw new AssertionError("Shadow renderPlayerArm was not remapped");
    }

    @Unique
    private void lt6$applyHand(PoseStack pose, HumanoidArm physicalArm) {
        boolean main = this.minecraft.player != null
                && physicalArm == this.minecraft.player.getMainArm();
        double x = main ? Cfg.MAIN_HAND_X.v : Cfg.OFF_HAND_X.v;
        double y = main ? Cfg.MAIN_HAND_Y.v : Cfg.OFF_HAND_Y.v;
        double z = main ? Cfg.MAIN_HAND_Z.v : Cfg.OFF_HAND_Z.v;
        float size = (float)(main ? Cfg.MAIN_HAND_SIZE.v : Cfg.OFF_HAND_SIZE.v);
        pose.translate(x, y, z);
        pose.scale(size, size, size);
    }

    // Arms drawn normally when the main slot is empty and for single-hand
    // maps. These hooks cannot affect items — they run inside arm rendering.
    @Inject(method = "renderPlayerArm", at = @At("HEAD"), require = 1)
    private void lt6$armBegin(PoseStack pose, SubmitNodeCollector collector,
            int light, float equip, float swing, HumanoidArm arm, CallbackInfo ci) {
        if (!Cfg.HAND_VIEW.enabled || this.minecraft.player == null) return;
        pose.pushPose();
        lt6$applyHand(pose, arm);
    }

    @Inject(method = "renderPlayerArm", at = @At("RETURN"), require = 1)
    private void lt6$armEnd(PoseStack pose, SubmitNodeCollector collector,
            int light, float equip, float swing, HumanoidArm arm, CallbackInfo ci) {
        if (this.minecraft.player != null) {
            ViewmodelHitboxes.capture(pose,
                    arm == this.minecraft.player.getMainArm(), false);
            if (Cfg.HAND_VIEW.enabled) pose.popPose();
        }
    }

    // Two-handed maps use renderMapHand instead of renderPlayerArm.
    @Inject(method = "renderMapHand", at = @At("HEAD"), require = 1)
    private void lt6$mapArmBegin(PoseStack pose, SubmitNodeCollector collector,
            int light, HumanoidArm arm, CallbackInfo ci) {
        if (!Cfg.HAND_VIEW.enabled || this.minecraft.player == null) return;
        pose.pushPose();
        lt6$applyHand(pose, arm);
    }

    @Inject(method = "renderMapHand", at = @At("RETURN"), require = 1)
    private void lt6$mapArmEnd(PoseStack pose, SubmitNodeCollector collector,
            int light, HumanoidArm arm, CallbackInfo ci) {
        if (this.minecraft.player != null) {
            ViewmodelHitboxes.capture(pose,
                    arm == this.minecraft.player.getMainArm(), false);
            if (Cfg.HAND_VIEW.enabled) pose.popPose();
        }
    }

    /**
     * Vanilla only draws the bare MAIN hand when empty; empty OFF hand is
     * omitted entirely. Allow the player to show and edit it independently.
     * Optionally draw an arm beside a held item; no parent matrix wrapping.
     */
    @Inject(method = "renderArmWithItem", at = @At("HEAD"), require = 1)
    private void lt6$optionalArm(AbstractClientPlayer player, float partialTick,
            float pitch, InteractionHand hand, float swing, ItemStack item,
            float equip, PoseStack pose, SubmitNodeCollector collector,
            int light, CallbackInfo ci) {
        if (!Cfg.HAND_VIEW.enabled || player.isInvisible() || player.isScoping()) return;
        boolean empty = item.isEmpty();
        boolean extraEmpty = empty && hand == InteractionHand.OFF_HAND
                && Cfg.SHOW_EMPTY_OFFHAND.v;
        boolean besideItem = !empty && Cfg.SHOW_HAND_WITH_ITEM.v
                && !item.has(DataComponents.MAP_ID);
        if (!extraEmpty && !besideItem) return;
        if (extraEmpty && player.getMainHandItem().has(DataComponents.MAP_ID))
            return; // The two-handed map already renders both arms.

        HumanoidArm side = hand == InteractionHand.MAIN_HAND
                ? player.getMainArm() : player.getMainArm().getOpposite();
        pose.pushPose();
        try {
            // Calls the vanilla arm model, including LT6's arm-only transform.
            this.renderPlayerArm(pose, collector, light, equip, swing, side);
        } finally {
            pose.popPose();
        }
    }

    @Unique
    private static boolean lt6$isFirstPerson(ItemDisplayContext mode) {
        return mode == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                || mode == ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
    }

    @Unique
    private static boolean lt6$mainItem(LivingEntity entity, ItemDisplayContext mode) {
        return (mode == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND)
                == (entity.getMainArm() == HumanoidArm.RIGHT);
    }

    // Independent item path; no hand pose is changed by this injection.
    @Inject(method = "renderItem", at = @At("HEAD"), require = 1)
    private void lt6$itemBegin(LivingEntity entity, ItemStack item,
            ItemDisplayContext mode, PoseStack pose, SubmitNodeCollector collector,
            int light, CallbackInfo ci) {
        if (item.isEmpty() || !lt6$isFirstPerson(mode)) return;
        boolean main = lt6$mainItem(entity, mode);
        if (!Cfg.ITEM_VIEW.enabled) {
            ViewmodelHitboxes.capture(pose, main, true);
            return;
        }
        double x = main ? Cfg.ITEM_VIEW_X.v : Cfg.ITEM_OFF_X.v;
        double y = main ? Cfg.ITEM_VIEW_Y.v : Cfg.ITEM_OFF_Y.v;
        double z = main ? Cfg.ITEM_VIEW_Z.v : Cfg.ITEM_OFF_Z.v;
        float size = (float)(main ? Cfg.ITEM_VIEW_SIZE.v : Cfg.ITEM_OFF_SIZE.v);
        pose.pushPose();
        pose.translate(x, y, z);
        pose.scale(size, size, size);
        ViewmodelHitboxes.capture(pose, main, true);
    }

    @Inject(method = "renderItem", at = @At("RETURN"), require = 1)
    private void lt6$itemEnd(LivingEntity entity, ItemStack item,
            ItemDisplayContext mode, PoseStack pose, SubmitNodeCollector collector,
            int light, CallbackInfo ci) {
        if (Cfg.ITEM_VIEW.enabled && !item.isEmpty() && lt6$isFirstPerson(mode))
            pose.popPose();
    }
}
