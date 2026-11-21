package dev.lt6.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.lt6.client.HitColorRender;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replace the standard armour submission, including dyed layers, glint and
 * trims, by an opaque monochrome submission while the player is being hit.
 * The default armour path is untouched at all other times.
 */
@Mixin(HumanoidArmorLayer.class)
public abstract class HitArmorMixin {
    @Shadow @Final private ArmorModelSet<?> modelSet;
    @Shadow @Final private ArmorModelSet<?> babyModelSet;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "renderArmorPiece(" +
            "Lcom/mojang/blaze3d/vertex/PoseStack;" +
            "Lnet/minecraft/client/renderer/SubmitNodeCollector;" +
            "Lnet/minecraft/world/item/ItemStack;" +
            "Lnet/minecraft/world/entity/EquipmentSlot;I" +
            "Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void lt6$solidArmor(PoseStack pose, SubmitNodeCollector collector,
                                ItemStack stack, EquipmentSlot slot, int light,
                                HumanoidRenderState state, CallbackInfo ci) {
        if (!HitColorRender.active(state)) return;
        if (HumanoidArmorLayer.shouldRender(stack, slot)) {
            Model armorModel = (Model) (state.isBaby ? babyModelSet : modelSet).get(slot);
            HitColorRender.submitSolid(collector, armorModel, state, pose, state.outlineColor);
        }
        ci.cancel();
    }
}
