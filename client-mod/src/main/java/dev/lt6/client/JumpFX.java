package dev.lt6.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.DustParticleOptions;

public final class JumpFX {
    private static boolean wasGrounded=true;
    private static boolean init;
    private JumpFX() {}
    public static void tick(Minecraft mc) {
        if(mc.player==null || mc.level==null) {init=false;return;}
        boolean ground=mc.player.onGround();
        if(!init) {wasGrounded=ground;init=true;return;}
        if(Cfg.JUMP_CIRCLES.enabled && wasGrounded!=ground) {
            if(!ground && mc.player.getDeltaMovement().y>0.06 || ground) ring(mc);
        }
        wasGrounded=ground;
    }
    private static void ring(Minecraft mc) {
        var p=mc.player;
        var color=new DustParticleOptions(Cfg.JUMP_TINT.rgb(),1f);
        double r=Cfg.JUMP_RADIUS.v;
        for(int i=0;i<32;i++) {
            double a=i*Math.PI/16;
            mc.level.addParticle(color,p.getX()+Math.cos(a)*r,p.getY()+0.04,
              p.getZ()+Math.sin(a)*r,0,0.008,0);
        }
    }
}
