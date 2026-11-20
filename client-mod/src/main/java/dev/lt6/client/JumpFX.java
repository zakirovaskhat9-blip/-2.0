package dev.lt6.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.DustParticleOptions;

/** A client-side growing ring for each jump and landing, rendered over several ticks. */
public final class JumpFX {
    private static final int DURATION = 18;
    private static final int MAX_WAVES = 3;
    private static final int PARTICLES_PER_WAVE_TICK = 18;
    private static final List<Wave> WAVES = new ArrayList<>();
    private static boolean wasGrounded = true;
    private static boolean initialized;
    private static class Wave {
        final double x, y, z;
        int age;
        Wave(double x, double y, double z) { this.x=x;this.y=y;this.z=z; }
    }
    private JumpFX() {}
    public static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            initialized = false; WAVES.clear(); return;
        }
        boolean ground = mc.player.onGround();
        if (!initialized) { wasGrounded = ground; initialized = true; }
        if (Cfg.JUMP_CIRCLES.enabled && wasGrounded != ground
                && (ground || mc.player.getDeltaMovement().y > 0.06)) {
            double floorY = ground ? mc.player.getY() + .06 : mc.player.getY() - .02;
            if (WAVES.size() >= MAX_WAVES) WAVES.remove(0);
            WAVES.add(new Wave(mc.player.getX(), floorY, mc.player.getZ()));
        }
        wasGrounded = ground;
        if (!Cfg.JUMP_CIRCLES.enabled) { WAVES.clear(); return; }
        DustParticleOptions dust = new DustParticleOptions(Cfg.JUMP_TINT.rgb(), 1.25f);
        Iterator<Wave> it = WAVES.iterator();
        while (it.hasNext()) {
            Wave w = it.next();
            double progress = (double) w.age / DURATION;
            double radius = Math.max(.05, Cfg.JUMP_RADIUS.v * (.12 + .88 * progress));
            // Emitting each tick makes a visible outward wave instead of a one-tick static circle.
            for (int j = 0; j < PARTICLES_PER_WAVE_TICK; j++) {
                double angle = (j + (w.age & 1) * .5) * (Math.PI * 2.0 / PARTICLES_PER_WAVE_TICK);
                mc.level.addParticle(dust, w.x + Math.cos(angle) * radius, w.y + 0.013,
                        w.z + Math.sin(angle) * radius, 0.0, 0.008, 0.0);
            }
            if (++w.age >= DURATION) it.remove();
        }
    }
}
