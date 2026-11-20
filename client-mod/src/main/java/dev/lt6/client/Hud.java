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
    // Monotonic angle with a smooth sinusoidal speed variation. Changing the slider must not
    // make the arrows snap to a new angle. Keeping a phase origin achieves that.
    private static final double TWO_PI = Math.PI * 2.0;
    private static long angleOriginNs = System.nanoTime();
    private static double angleOriginRadians = 0.0;
    private static double activeTurnSeconds = 3.0;
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

    /** An actual local attack attempt triggers a short recoil and red-tinted glow. */
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
        int tint = Gfx.mix(0xFF39FF14, 0xFFFF4652, recoil * 0.85f);
        Gfx.alphaMul = 1f;
        float angle = continuousAngle(System.nanoTime(), Cfg.ESP_TURN_SECONDS.v);
        drawMarker(graphics, centerX, centerY, radius * spawnScale * (1.0 + 0.16 * recoil), opacity, angle, tint, recoil);
    }

    /**
     * Nonuniform but smooth rotation in real time. Modulating the angle with a sine
     * gives speed between 40% and 160% of average, without reversing or snapping.
     * The stored base phase preserves continuity when the speed slider changes.
     */
    private static float continuousAngle(long nowNs, double turnSeconds) {
        double seconds = Math.max(0.5, turnSeconds);
        if (Math.abs(seconds - activeTurnSeconds) > 0.00001) {
            angleOriginRadians = angleOriginRadians
                    + (nowNs - angleOriginNs) * 1e-9 * (TWO_PI / activeTurnSeconds);
            angleOriginRadians %= TWO_PI;
            angleOriginNs = nowNs;
            activeTurnSeconds = seconds;
        }
        double angle = angleOriginRadians
                + (nowNs - angleOriginNs) * 1e-9 * (TWO_PI / activeTurnSeconds);
        return (float) ((angle + 0.60 * Math.sin(angle)) % TWO_PI);
    }

    private static double lerp(double t, double a, double b) {
        return a + (b - a) * t;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    /** Draw four ∧, ∨, < and > symbols pointing OUTWARDS, as in the reference. */
    private static void drawMarker(GuiGraphics g, double cx, double cy, double radius, float opacity, float angle, int tint, float recoil) {
        // A local coordinate system keeps thickness, spacing and glow in scale
        // with the target. Scale is never clamped to a minimum screen size.
        float scale = (float) (radius / 40.0);
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate((float) cx, (float) cy);
        pose.rotate(angle); // rotate the four chevrons as one, around the exact target-body anchor
        pose.scale(scale, scale);

        // Bloom is intentionally understated so arrow shapes remain crisp.
        for (int glow = 2; glow >= 1; glow--) {
            int color = Gfx.a(tint, opacity * (glow == 1 ? 0.20f : 0.08f) * (1f + 0.60f * recoil));
            chevrons(g, 4 + glow * 5, color);
        }
        chevrons(g, 4, Gfx.a(tint, opacity));
        pose.popMatrix();
    }

    private static void chevrons(GuiGraphics g, int width, int color) {
        // Top: ^
        stroke(g, -12, -28, 0, -40, width, color);
        stroke(g, 0, -40, 12, -28, width, color);
        // Right: >
        stroke(g, 28, -12, 40, 0, width, color);
        stroke(g, 40, 0, 28, 12, width, color);
        // Bottom: v
        stroke(g, -12, 28, 0, 40, width, color);
        stroke(g, 0, 40, 12, 28, width, color);
        // Left: <
        stroke(g, -28, -12, -40, 0, width, color);
        stroke(g, -40, 0, -28, 12, width, color);
    }

    /** Rotate a filled rectangle to produce a diagonal, solid line segment. */
    private static void stroke(GuiGraphics g, int x0, int y0, int x1, int y1, int width, int color) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        int length = (int) Math.round(Math.hypot(dx, dy));
        if (length == 0) return;
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(x0, y0);
        pose.rotate((float) Math.atan2(dy, dx));
        g.fill(0, -width / 2, length, (width + 1) / 2, color);
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
