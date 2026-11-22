package dev.lt6.client.mixin;

import dev.lt6.client.ChatHudEditor;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draw draggable HUD and viewmodel grips on top of the open vanilla chat. */
@Mixin(ChatScreen.class)
public abstract class LT6ChatEditMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void lt6$renderChatEditor(GuiGraphics graphics, int mx, int my,
                                      float delta, CallbackInfo ci) {
        ChatHudEditor.render(graphics, mx, my);
    }
}
