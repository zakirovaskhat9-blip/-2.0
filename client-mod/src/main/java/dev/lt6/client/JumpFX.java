package dev.lt6.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.DustParticleOptions;

/**
 * Client-only expanding particle waves centered under the feet.
 * Jump starts a ring, landing starts another; particles never alter the world.
 */
public final class JumpFX {
    private static final int MAX_WAVES = 5;
    private static final List<Wave> WAVES = new ArrayList<>();
    private static boolean grounded = true;
    private static boolean initialized;

    private static final class Wave {
        final double x, y, z;
        final int lifetime;
        int age;
        Wave(double x, double y, double z, int lifetime) {
            this.x = x; this.y = y; this.z = z; this.lifetime = lifetime;
        }
    }

    private JumpFX() {}

    public static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            initialized = false;
            WAVES.clear();
            return;
        }
        boolean nowGrounded = mc.player.onGround();
        if (!initialized) {
            grounded = nowGrounded;
            initialized = true;
        }

        boolean jumped = grounded && !nowGrounded && mc.player.getDeltaMovement().y > 0.06;
        boolean landed = !grounded && nowGrounded;
        grounded = nowGrounded;
        if (!Cfg.JUMP_CIRCLES.enabled) {
            WAVES.clear();
            return;
        }

        if (jumped || landed) {
            if (WAVES.size() >= MAX_WAVES) WAVES.remove(0);
            double y = landed ? mc.player.getY() + 0.05 : mc.player.getY() - 0.08;
            WAVES.add(new Wave(mc.player.getX(), y, mc.player.getZ(),
                    (int)Cfg.JUMP_LIFE.v));
        }
        DustParticleOptions dust = new DustParticleOptions(Cfg.JUMP_TINT.rgb(), 1.0f);
        DustParticleOptions sparks = new DustParticleOptions(Cfg.JUMP_TINT.rgb(), 0.72f);
        int count = (int)Cfg.JUMP_DENSITY.v;
        int mode = Cfg.JUMP_PATTERN.idx;
        Iterator<Wave> iter = WAVES.iterator();
        while (iter.hasNext()) {
            Wave wave = iter.next();
            double progress = wave.age / (double)wave.lifetime;
            double radius = Math.max(0.08, Cfg.JUMP_RADIUS.v * (0.15 + 0.85 * progress));
            int ringCount = mode == 2 ? 0 : count;
            for (int k = 0; k < ringCount; k++) {
                double theta = 2.0 * Math.PI * (k + (wave.age & 1) * 0.5) / ringCount;
                double dx = Math.cos(theta), dz = Math.sin(theta);
                mc.level.addParticle(dust,
                        wave.x + dx * radius,
                        wave.y + 0.018,
                        wave.z + dz * radius,
                        dx * 0.006, 0.0, dz * 0.006);
            }
            if (mode > 0 && wave.age <= wave.lifetime * 0.72) {
                int sparksPerTick = mode == 2 ? count / 2 : count / 4;
                for (int k = 0; k < sparksPerTick; k++) {
                    // Golden angle avoids random flicker and keeps the sparks symmetric.
                    double theta = (wave.age * 17.0 + k * 137.508) * (Math.PI / 180.0);
                    double dx = Math.cos(theta), dz = Math.sin(theta);
                    double rr = Math.max(0.1, radius * 0.65);
                    mc.level.addParticle(sparks, wave.x + dx * rr, wave.y + .035,
                            wave.z + dz * rr, dx * .025, .032, dz * .025);
                }
            }
            if (++wave.age >= wave.lifetime) iter.remove();
        }
    }
}
