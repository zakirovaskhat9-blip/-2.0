package dev.enchanted.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.entity.Entity;

public final class HitFX {
    private HitFX() {}
    public static void hit(Entity e) {
        Minecraft mc=Minecraft.getInstance();
        if(!Cfg.HIT_COLOR.enabled || mc.level==null || e==null) return;
        var part=new DustParticleOptions(Cfg.HIT_TINT.rgb(), (float)Cfg.HIT_SIZE.v);
        for(int i=0;i<18;i++) {
            double a=i*(Math.PI*2/18);
            mc.level.addParticle(part,e.getX()+Math.cos(a)*e.getBbWidth()*0.6,
                e.getY()+e.getBbHeight()*0.55+Math.sin(i*2.0)*0.1,
                e.getZ()+Math.sin(a)*e.getBbWidth()*0.6,
                Math.cos(a)*0.025,0.015,Math.sin(a)*0.025);
        }
    }
}
