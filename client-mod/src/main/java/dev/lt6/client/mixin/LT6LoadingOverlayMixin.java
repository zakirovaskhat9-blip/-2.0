package dev.lt6.client.mixin;

import dev.lt6.client.SkyPacks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft's LoadingOverlay.tick() waits for fadeInStart to be initialized
 * by render() before it runs the reload completion callback. Cancelling the
 * vanilla render without setting that timer deadlocked every pack switch
 * (the overlay swallowed mouse input forever). Keep the vanilla lifecycle.
 */
@Mixin(LoadingOverlay.class)
public abstract class LT6LoadingOverlayMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private boolean fadeIn;
    @Shadow private long fadeInStart;
    @Shadow private long fadeOutStart;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void lt6$hideReloadGraphics(GuiGraphics graphics, int mouseX, int mouseY,
                                        float delta, CallbackInfo ci) {
        if (!SkyPacks.isSilentReload()) return;

        final long now = Util.getMillis();
        // Absolutely essential: LoadingOverlay.tick() requires fadeInStart.
        if (this.fadeIn && this.fadeInStart == -1L) this.fadeInStart = now;

        // Keep the previous game/menu visible; resource reload and callbacks
        // continue in Minecraft's own LoadingOverlay.tick().
        if (this.minecraft.screen != null)
            this.minecraft.screen.renderWithTooltipAndSubtitles(graphics, mouseX, mouseY, delta);
        else
            this.minecraft.gui.renderDeferredSubtitles();

        // The reload is complete and onFinish has reinitialized the screen.
        // No visible fade is needed: release input immediately, not 2 seconds later.
        if (this.fadeOutStart >= 0L)
            this.minecraft.setOverlay(null);
        ci.cancel();
    }
}
