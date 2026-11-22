package dev.lt6.client.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import java.util.Map;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Access the live post pass UBOs without modifying Minecraft's shader loader. */
@Mixin(PostPass.class)
public interface LT6PostPassAccess {
    @Accessor("customUniforms")
    Map<String, GpuBuffer> lt6$getCustomUniforms();
}
