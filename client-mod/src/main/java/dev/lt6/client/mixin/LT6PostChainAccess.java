package dev.lt6.client.mixin;

import java.util.List;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Access post passes so LT6 can update its own GPU colour uniform buffer. */
@Mixin(PostChain.class)
public interface LT6PostChainAccess {
    @Accessor("passes")
    List<PostPass> lt6$getPasses();
}
