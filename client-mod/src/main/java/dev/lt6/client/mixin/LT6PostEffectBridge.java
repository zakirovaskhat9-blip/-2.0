package dev.lt6.client.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Accessor for the private Mojang 1.21.11 GameRenderer.setPostEffect. */
@Mixin(GameRenderer.class)
public interface LT6PostEffectBridge {
    @Invoker("setPostEffect")
    void lt6$setPostEffect(Identifier id);
}
