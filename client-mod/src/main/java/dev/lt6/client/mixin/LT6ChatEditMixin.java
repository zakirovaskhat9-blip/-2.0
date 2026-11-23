package dev.lt6.client.mixin;

import dev.lt6.client.ChatHudEditor;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Use direct ChatScreen input, not Fabric's allowMouse event whose cancellation
 * can prevent later drag/release callbacks from seeing the selected hand.
 * Never render grips or intercept the chat input box.
 */
@Mixin(ChatScreen.class)
public abstract class LT6ChatEditMixin extends Screen {
    protected LT6ChatEditMixin(Component title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"))
    private void lt6$initEditor(CallbackInfo ci) {
        ChatHudEditor.onChatOpened();
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void lt6$click(MouseButtonEvent event, boolean doubleClick,
            CallbackInfoReturnable<Boolean> ci) {
        if (ChatHudEditor.click(event)) ci.setReturnValue(true);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (ChatHudEditor.drag(event)) return true;
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (ChatHudEditor.release(event)) return true;
        return super.mouseReleased(event);
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void lt6$wheel(double x, double y, double horizontal, double vertical,
            CallbackInfoReturnable<Boolean> ci) {
        if (ChatHudEditor.scroll(x, y, vertical)) ci.setReturnValue(true);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void lt6$renderChatEditor(GuiGraphics graphics, int mx, int my,
            float delta, CallbackInfo ci) {
        ChatHudEditor.render(graphics, mx, my);
    }
}
