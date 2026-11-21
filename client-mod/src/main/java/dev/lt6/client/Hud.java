package dev.lt6.client;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;

/**
 * LT6 Target ESP: four neon-green chevrons orbit the torso with smoothly varying speed,
 * without a center dot. The orbit stays circular in GUI pixels regardless of aspect ratio.
 *
 * The marker follows the interpolated WORLD position of the target's torso. Its
 * size is derived from the projected HEIGHT of that entity (not from a constant
 * number of screen pixels), so it gets smaller as the entity moves away.
 * No smoothing is applied to the projected position: delayed smoothing makes
 * a screen-space overlay visibly lag behind a moving player or camera.
 */
public final class Hud {
    private static final long HOLD_MS = 4000L;
    private static final long FADE_IN_MS = 150L;
    private static final long FADE_OUT_MS = 260L;
    private static final long HIT_RECOIL_MS = 340L;
    // Five full turns clockwise, then five counterclockwise, with smooth zero-speed reversals.
    // Velocity follows smooth acceleration -> 1 second at peak -> rapid but
    // smooth braking. At the turn-around speed reaches zero before reversing.
    private static final double TWO_PI = Math.PI * 2.0;
    private static final double TURNS_PER_DIRECTION = 5.0;
    private static final double RAMP_UP = 0.78;
    private static final double HOLD_PEAK = 0.80;
    private static final double RAMP_DOWN = 0.62;
    private static final double HALF_CYCLE = RAMP_UP + HOLD_PEAK + RAMP_DOWN;
    private static final double PEAK_RADIANS_PER_SECOND =
            TURNS_PER_DIRECTION * TWO_PI / (0.5 * RAMP_UP + HOLD_PEAK + 0.5 * RAMP_DOWN);
    private static long lastFrameNs;
    private static double rotationClockSeconds;
    private static LivingEntity target;
    private static long acquiredAt, expiresAt, lastAttackAt;

    // Camera basis calculated once per frame.
    private static double camX, camY, camZ;
    private static double forwardX, forwardY, forwardZ;
    private static double rightX, rightZ;
    private static double upX, upY, upZ;
    private static double halfFovTan, aspect;
    private static int screenW, screenH;
    private static double projectedX, projectedY;

    private Hud() { }

    static void hit(Entity e) {
        if (!(e instanceof LivingEntity living) || !living.isAlive()) return;
        long now = System.currentTimeMillis();
        if (living != target || now >= expiresAt) {
            acquiredAt = now;
            lastAttackAt = 0L;
        }
        target = living;
        expiresAt = now + HOLD_MS;
    }

    /** An actual attack attempt triggers recoil, with an optional red flash. */
    static void attack(Entity e) {
        hit(e);
        if (e instanceof LivingEntity && e == target) lastAttackAt = System.currentTimeMillis();
    }

    public static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        // A visible entity under the crosshair is also a valid target.
        // Attacks keep that same target highlighted briefly after looking away.
        if (minecraft.hitResult instanceof EntityHitResult hitResult
                && hitResult.getEntity() instanceof LivingEntity hovered
                && hovered != minecraft.player) {
            hit(hovered);
        }
        LivingEntity current = target;
        if (current == null || !Cfg.TARGET_ESP.enabled || minecraft.level == null
                || minecraft.player == null || minecraft.options.hideGui) return;

        long now = System.currentTimeMillis();
        if (now >= expiresAt || current.isRemoved() || !current.isAlive()
                || current.level() != minecraft.level || current == minecraft.player) {
            target = null;
            return;
        }

        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (camera == null || !camera.isInitialized()) return;
        screenW = minecraft.getWindow().getGuiScaledWidth();
        screenH = minecraft.getWindow().getGuiScaledHeight();
        if (screenW <= 0 || screenH <= 0) return;
        prepare(camera, minecraft.options.fov().get());

        // Interpolate between game ticks. This also follows walking, sprinting,
        // jumping and knockback while the target and the camera are moving.
        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        double x = lerp(partialTick, current.xo, current.getX());
        double y = lerp(partialTick, current.yo, current.getY());
        double z = lerp(partialTick, current.zo, current.getZ());
        double height = current.getBbHeight();
        if (height <= 0) return;

        // Torso center rather than the head, feet, crosshair or a fixed GUI spot.
        if (!project(x, y + height * 0.55, z)) return;
        double centerX = projectedX;
        double centerY = projectedY;
        if (centerX < -64 || centerX > screenW + 64
                || centerY < -64 || centerY > screenH + 64) return;

        // Derive size directly from the target's world-space height and camera depth.
        // Projecting the feet and head separately made the radius wobble while looking
        // up/down. This stays proportional to the visible player's apparent size.
        double dx = x - camX;
        double dy = y + height * 0.55 - camY;
        double dz = z - camZ;
        double depth = dx * forwardX + dy * forwardY + dz * forwardZ;
        if (depth <= 0.05) return;
        double projectedHeight = height * screenH / (2.0 * depth * halfFovTan);
        if (!Double.isFinite(projectedHeight)) return;
        double radius = projectedHeight * 0.145 * Cfg.ESP_SIZE.v;
        if (radius < 1.5) return; // Do not inflate distant targets.

        float fadeIn = clamp01((now - acquiredAt) / (float) FADE_IN_MS);
        float fadeOut = clamp01((expiresAt - now) / (float) FADE_OUT_MS);
        float opacity = fadeIn * fadeOut;
        if (opacity <= 0.01f) return;

        // Spawn and loss easing affect only scale/opacity, never the world-space anchor.
        float enter = fadeIn * fadeIn * (3f - 2f * fadeIn);
        float spawnScale = 0.18f + 0.82f * enter;
        float recoil = lastAttackAt <= 0 ? 0f : clamp01(1f - (now - lastAttackAt) / (float) HIT_RECOIL_MS);
        recoil *= recoil;
        int baseColor = Cfg.ESP_STYLE.idx == 1 ? Cfg.ESP_DIAMOND_TINT.rgb() : Cfg.ESP_TINT.rgb();
        int tint = Gfx.mix(0xFF000000 | baseColor, 0xFFFF4652,
                Cfg.ESP_RED_ON_HIT.v ? recoil : 0f);
        Gfx.alphaMul = 1f;
        if (Cfg.ESP_STYLE.idx == 2) {
            CelestialSpirits.render(graphics, camera, x, y, z, height,
                    screenW, screenH, minecraft.options.fov().get(), opacity,
                    enter, Cfg.ESP_SIZE.v, Cfg.ESP_GLOW.v);
            return;
        }
        float angle = continuousAngle(System.nanoTime(), Cfg.ESP_TURN_SECONDS.v);
        drawMarker(graphics, centerX, centerY, radius * spawnScale * (1.0 + 0.16 * recoil),
                opacity, angle, tint, recoil);
    }

    /**
     * The animation phase is integrated frame-to-frame so changing the speed
     * setting never teleports the marker. Each half-cycle has exactly five
     * revolutions and a smooth reversal at zero angular velocity.
     *
     * The speed slider is the peak seconds/turn. Default 0.25 gives a
     * short high-speed plateau, with 0.78s acceleration and 0.62s braking.
     */
    private static float continuousAngle(long nowNs, double turnSeconds) {
        if (lastFrameNs == 0L) lastFrameNs = nowNs;
        double frameSeconds = Math.max(0.0, Math.min(0.10, (nowNs - lastFrameNs) * 1e-9));
        lastFrameNs = nowNs;
        double timeScale = 0.25 / Math.max(0.15, turnSeconds);
        rotationClockSeconds = (rotationClockSeconds + frameSeconds * timeScale) % (2.0 * HALF_CYCLE);

        boolean reverse = rotationClockSeconds >= HALF_CYCLE;
        double t = reverse ? rotationClockSeconds - HALF_CYCLE : rotationClockSeconds;
        double traveled;
        if (t < RAMP_UP) {
            double u = t / RAMP_UP;
            // Integral of smoothstep(0 -> 1): continuous speed and acceleration.
            traveled = PEAK_RADIANS_PER_SECOND * RAMP_UP * (u * u * u - 0.5 * u * u * u * u);
        } else if (t < RAMP_UP + HOLD_PEAK) {
            traveled = PEAK_RADIANS_PER_SECOND * (0.5 * RAMP_UP + t - RAMP_UP);
        } else {
            double u = (t - RAMP_UP - HOLD_PEAK) / RAMP_DOWN;
            // Integral of 1 - smoothstep(0 -> 1), ending at zero angular speed.
            traveled = PEAK_RADIANS_PER_SECOND * (0.5 * RAMP_UP + HOLD_PEAK
                    + RAMP_DOWN * (u - u * u * u + 0.5 * u * u * u * u));
        }
        return (float) (reverse ? -traveled : traveled);
    }

    private static double lerp(double t, double a, double b) {
        return a + (b - a) * t;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    /** Draw four smoothly rounded, supersampled chevrons instead of thick rectangles. */
    private static void drawMarker(GuiGraphics g, double cx, double cy, double radius,
                                   float opacity, float angle, int tint, float recoil) {
        float scale = (float) (radius / 40.0);
        float glowPulse = (float) (0.94 + 0.06 * Math.sin(System.nanoTime() * 1.6e-9));
        double glow = Cfg.ESP_GLOW.v * (glowPulse + recoil * 0.45f);
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate((float) cx, (float) cy);
        pose.rotate(angle);
        pose.scale(scale, scale);
        TargetEspSprite.render(g, tint, opacity, glow, Cfg.ESP_STYLE.idx);
        pose.popMatrix();
    }

    private static void prepare(Camera camera, double fovDegrees) {
        camX = camera.position().x;
        camY = camera.position().y;
        camZ = camera.position().z;
        double yaw = Math.toRadians(camera.yRot());
        double pitch = Math.toRadians(camera.xRot());
        double cosPitch = Math.cos(pitch);
        double sinYaw = Math.sin(yaw);
        double cosYaw = Math.cos(yaw);
        forwardX = -sinYaw * cosPitch;
        forwardY = -Math.sin(pitch);
        forwardZ = cosYaw * cosPitch;
        rightX = -cosYaw;
        rightZ = -sinYaw;
        upX = -rightZ * forwardY;
        upY = rightZ * forwardX - rightX * forwardZ;
        upZ = rightX * forwardY;
        halfFovTan = Math.tan(Math.toRadians(fovDegrees) / 2.0);
        aspect = (double) screenW / screenH;
    }

    private static boolean project(double wx, double wy, double wz) {
        double dx = wx - camX;
        double dy = wy - camY;
        double dz = wz - camZ;
        double depth = dx * forwardX + dy * forwardY + dz * forwardZ;
        if (depth <= 0.05) return false;
        double normalizedX = (dx * rightX + dz * rightZ) / (depth * halfFovTan * aspect);
        double normalizedY = (dx * upX + dy * upY + dz * upZ) / (depth * halfFovTan);
        projectedX = (normalizedX * 0.5 + 0.5) * screenW;
        projectedY = (0.5 - normalizedY * 0.5) * screenH;
        return Double.isFinite(projectedX) && Double.isFinite(projectedY);
    }
}
