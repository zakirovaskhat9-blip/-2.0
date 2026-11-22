package dev.lt6.client;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.lt6.client.mixin.LT6PostChainAccess;
import dev.lt6.client.mixin.LT6PostEffectBridge;
import dev.lt6.client.mixin.LT6PostPassAccess;
import java.nio.ByteBuffer;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Independent live brightness, contrast, temperature, saturation, vibrance and
 * blend strength. Changes update a 32-byte GPU uniform block each client tick
 * instead of swapping 231 precompiled combinations. No HUD tinting.
 */
public final class ColorFX {
    private static final Logger LOGGER = LoggerFactory.getLogger("LT6/ColorFX");
    private static final Identifier GRADING_ID =
            Identifier.fromNamespaceAndPath("lt6", "grading/live");
    private static Identifier ownedId;
    private static boolean failed;

    private ColorFX() {}

    public static void tick(Minecraft mc) {
        Identifier present = mc.gameRenderer.currentPostEffect();
        boolean ours = ownedId != null && ownedId.equals(present);
        boolean neutral = Cfg.GRADE_BRIGHTNESS.v == 1.0
                && Cfg.GRADE_CONTRAST.v == 1.0
                && Cfg.GRADE_WARMTH.v == 0.0
                && Cfg.GRADE_SATURATION.v == 100.0
                && Cfg.GRADE_VIBRANCE.v == 100.0;
        boolean wanted = !failed && Cfg.GRADING.enabled
                && Cfg.GRADE_INTENSITY.v > 0.0 && !neutral
                && mc.player != null && mc.level != null;

        if (!wanted) {
            if (ours) mc.gameRenderer.clearPostEffect();
            ownedId = null;
            return;
        }
        // Never replace spectator effects or post effects owned by another mod.
        if (present != null && !ours) {
            ownedId = null;
            return;
        }

        try {
            if (!ours) {
                ((LT6PostEffectBridge) mc.gameRenderer).lt6$setPostEffect(GRADING_ID);
                ownedId = GRADING_ID;
            }
            PostChain chain = mc.getShaderManager().getPostChain(
                    GRADING_ID, LevelTargetBundle.MAIN_TARGETS);
            if (chain == null) return; // shader can still be loading after resource reload
            for (PostPass pass : ((LT6PostChainAccess) chain).lt6$getPasses()) {
                Map<String, GpuBuffer> uniforms =
                        ((LT6PostPassAccess) pass).lt6$getCustomUniforms();
                GpuBuffer buffer = uniforms.get("GradingConfig");
                if (buffer == null) continue; // skip Minecraft's final blit pass
                if ((buffer.usage() & GpuBuffer.USAGE_COPY_DST) == 0) {
                    buffer.close();
                    buffer = RenderSystem.getDevice().createBuffer(
                            () -> "LT6 live colour grade",
                            GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                            32L);
                    uniforms.put("GradingConfig", buffer);
                }

                try (MemoryStack stack = MemoryStack.stackPush()) {
                    ByteBuffer bytes = stack.malloc(32);
                    bytes.putFloat((float) (Cfg.GRADE_SATURATION.v / 100.0));
                    bytes.putFloat((float) Cfg.GRADE_CONTRAST.v);
                    bytes.putFloat((float) (Cfg.GRADE_WARMTH.v / 100.0));
                    bytes.putFloat((float) Cfg.GRADE_BRIGHTNESS.v);
                    bytes.putFloat((float) (Cfg.GRADE_INTENSITY.v / 100.0));
                    bytes.putFloat((float) (Cfg.GRADE_VIBRANCE.v / 100.0));
                    bytes.putFloat(0f);
                    bytes.putFloat(0f);
                    bytes.flip();
                    RenderSystem.getDevice().createCommandEncoder()
                            .writeToBuffer(buffer.slice(), bytes);
                }
            }
        } catch (RuntimeException e) {
            failed = true;
            LOGGER.error("LT6 live colour grading failed; disabling the effect", e);
            if (ownedId != null && ownedId.equals(mc.gameRenderer.currentPostEffect())) {
                mc.gameRenderer.clearPostEffect();
            }
            ownedId = null;
        }
    }
}
