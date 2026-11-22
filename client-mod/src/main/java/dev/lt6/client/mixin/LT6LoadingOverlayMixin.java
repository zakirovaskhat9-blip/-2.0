package dev.lt6.client.mixin;

import dev.lt6.client.SkyPacks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hide the red reload overlay only for LT6 Custom Sky; keep vanilla tick and errors. */
@Mixin(LoadingOverlay.class)
public abstract class LT6LoadingOverlayMixin {
    @Shadow private Minecraft minecraft;
    @Shadow private long fadeOutStart;
    @Shadow @Final private boolean fadeIn;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void lt6$hideSkyReload(GuiGraphics graphics, int mouseX, int mouseY,
                                   float delta, CallbackInfo ci) {
        // Hide all manual texture-pack reload splashes, but never the initial boot or recovery overlay.
        if (!SkyPacks.isSilentReload() && !this.fadeIn) return;
        if (this.minecraft.screen != null)
            this.minecraft.screen.renderWithTooltipAndSubtitles(graphics, mouseX, mouseY, delta);
        if (this.fadeOutStart > 0L && Util.getMillis() - this.fadeOutStart >= 2000L)
            this.minecraft.setOverlay(null);
        ci.cancel();
    }
}
