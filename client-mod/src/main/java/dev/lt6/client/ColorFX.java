package dev.lt6.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** Screen-level color grading preview. Disabled while Custom Sky is on to avoid tinting panoramas. */
public final class ColorFX {
    private ColorFX() {}
    private static Double rememberedGamma;
    public static void tick(Minecraft mc) {
        try {
            var gamma=mc.options.gamma();
            if(Cfg.BRIGHTNESS.enabled) {
                if(rememberedGamma==null) rememberedGamma=gamma.get();
                double v=Cfg.BRIGHTNESS_LEVEL.v;
                if(Math.abs(gamma.get()-v)>0.01) gamma.set(v);
            } else if(rememberedGamma!=null) {
                gamma.set(rememberedGamma); rememberedGamma=null;
            }
        } catch(Exception ignored) { }
    }
    public static void render(GuiGraphics g) {
        Minecraft mc=Minecraft.getInstance();
        if(mc.player==null || mc.level==null || mc.options.hideGui || Cfg.SKY.enabled) return;
        int w=mc.getWindow().getGuiScaledWidth();
        int h=mc.getWindow().getGuiScaledHeight();
        if(Cfg.FILTER.enabled) {
            double v=Cfg.WARMTH.v;
            int alpha=(int)Math.min(65,Math.abs(v)*0.55);
            if(alpha>0) g.fill(0,0,w,h,(alpha<<24) | (v>0?0xFF9B47:0x3876F8));
        }
        if(Cfg.CONTRAST.enabled) {
            double v=Cfg.CONTRAST_POWER.v;
            int alpha=(int)Math.min(44,Math.abs(v)*0.65);
            if(alpha>0) g.fill(0,0,w,h,(alpha<<24) | (v>0?0x000000:0xFFFFFF));
        }
    }
}
