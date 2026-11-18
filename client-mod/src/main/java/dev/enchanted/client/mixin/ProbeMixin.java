package dev.enchanted.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.enchanted.client.Cfg;
import dev.enchanted.client.SkyLib;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.EnvironmentAttributes;

/** Custom Sky: подменяем цвет неба, тумана, облаков и яркость звёзд. */
@Mixin(EnvironmentAttributeProbe.class)
public abstract class ProbeMixin {
	@SuppressWarnings("unchecked")
	@Inject(method = "getValue", at = @At("RETURN"), cancellable = true)
	private <V> void enchanted$sky(EnvironmentAttribute<V> attr, float partialTick, CallbackInfoReturnable<V> cir) {
		if (!Cfg.SKY.enabled) return;
		Object orig = cir.getReturnValue();
		Object res = null;
		SkyLib.Def d = SkyLib.current(Cfg.SKY_PRESET.idx);
		if (attr == EnvironmentAttributes.SKY_COLOR) {
			res = paint(orig, d != null ? d.sky() : Cfg.SKY_COLOR.rgb());
		} else if (attr == EnvironmentAttributes.FOG_COLOR && (d != null || Cfg.SKY_FOG.v)) {
			res = paint(orig, d != null ? d.fog() : Cfg.SKY_COLOR.rgb());
		} else if (attr == EnvironmentAttributes.CLOUD_COLOR) {
			res = Integer.valueOf(0); // полностью прозрачные облака = облаков нет
		} else if (attr == EnvironmentAttributes.STAR_BRIGHTNESS) {
			// плоское небо: объёмные звёзды выключены, поэтому они не дрожат при движении
			if (d != null) res = Float.valueOf(Cfg.SKY_STARS.v && d.stars ? 1.0f : 0.0f);
			else if (Cfg.SKY_STARS.v) res = Float.valueOf(1.0f);
		}
		if (res != null) cir.setReturnValue((V) res);
	}

	private static Object paint(Object orig, int rgb) {
		int alpha = orig instanceof Integer i && (i >>> 24) != 0 ? (i & 0xFF000000) : 0xFF000000;
		return Integer.valueOf(alpha | (rgb & 0xFFFFFF));
	}
}
