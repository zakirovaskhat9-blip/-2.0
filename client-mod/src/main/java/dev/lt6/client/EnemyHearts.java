package dev.lt6.client;

import java.util.Locale;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Health display above the heads of other players, and a targeted mob.
 * Uses the same world-to-screen projection as Target ESP, with render-frame
 * interpolation to prevent nameplates jumping between game ticks.
 */
final class EnemyHearts {
    private static final double MAX_RANGE_SQ = 48.0 * 48.0;
    private static final int MAX_MARKERS = 32;

    private EnemyHearts() {}

    static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!Cfg.ENEMY_HEARTS.enabled || mc.player == null || mc.level == null
                || mc.options.hideGui) return;
        Camera cam = mc.gameRenderer.getMainCamera();
        if (cam == null || !cam.isInitialized()) return;

        double partial = delta.getGameTimeDeltaPartialTick(false);
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        if (sw < 1 || sh < 1) return;

        double yaw = Math.toRadians(cam.yRot());
        double pitch = Math.toRadians(cam.xRot());
        double cp = Math.cos(pitch);
        double fx = -Math.sin(yaw) * cp;
        double fy = -Math.sin(pitch);
        double fz = Math.cos(yaw) * cp;
        double rx = -Math.cos(yaw);
        double rz = -Math.sin(yaw);
        double ux = -rz * fy;
        double uy = rz * fx - rx * fz;
        double uz = rx * fy;
        double hfov = Math.max(.01, Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0));
        double aspect = (double) sw / sh;
        int count = 0;

        for (Player enemy : mc.level.players()) {
            if (count >= MAX_MARKERS) break;
            if (enemy == mc.player || !enemy.isAlive() || enemy.isRemoved()
                    || enemy.distanceToSqr(mc.player) > MAX_RANGE_SQ) continue;
            draw(g, mc, enemy, cam, partial, sw, sh, fx, fy, fz,
                    rx, rz, ux, uy, uz, hfov, aspect);
            count++;
        }
        if (mc.hitResult instanceof EntityHitResult result
                && result.getEntity() instanceof LivingEntity focused
                && !(focused instanceof Player) && focused.isAlive()
                && focused.distanceToSqr(mc.player) <= MAX_RANGE_SQ) {
            draw(g, mc, focused, cam, partial, sw, sh, fx, fy, fz,
                    rx, rz, ux, uy, uz, hfov, aspect);
        }
    }

    private static void draw(GuiGraphics g, Minecraft mc, LivingEntity e, Camera camera,
                             double partial, int sw, int sh,
                             double fx, double fy, double fz, double rx, double rz,
                             double ux, double uy, double uz, double tanHalfFov, double aspect) {
        double x = e.xo + (e.getX() - e.xo) * partial - camera.position().x;
        double y = e.yo + (e.getY() - e.yo) * partial
                + e.getBbHeight() + 0.32 - camera.position().y;
        double z = e.zo + (e.getZ() - e.zo) * partial - camera.position().z;
        double depth = x * fx + y * fy + z * fz;
        if (depth <= .05) return;

        double px = (0.5 + .5 * (x * rx + z * rz) / (depth * tanHalfFov * aspect)) * sw;
        double py = (0.5 - .5 * (x * ux + y * uy + z * uz) / (depth * tanHalfFov)) * sh;
        if (!Double.isFinite(px) || !Double.isFinite(py)
                || px < -150 || px > sw + 150 || py < -70 || py > sh + 70) return;

        float max = Math.max(1f, e.getMaxHealth());
        float health = Math.max(0f, Math.min(max, e.getHealth()));
        float absorption = Math.max(0f, e.getAbsorptionAmount());
        int totalHearts = Math.min(12, Math.max(1, (int)Math.ceil(max / 2.0)));
        int filledHearts = Math.min(totalHearts,
                (int) Math.ceil(health / max * totalHearts));

        // Cap glyphs to twelve for bosses; the numeric reading remains exact.
        String number = String.format(Locale.ROOT, " %.1f/%.1f", health, max);
        if (absorption > 0.05f) number += String.format(Locale.ROOT, " +%.1f", absorption);
        int rowWidth = totalHearts * 10 + mc.font.width(number);
        String name = e.getName().getString();
        if (name.length() > 18) name = name.substring(0, 18);
        int nameWidth = mc.font.width(name);
        int cardW = Math.max(rowWidth, nameWidth) + 16;
        int cardH = 30;
        double scale = Math.max(.35, Math.min(2.2, Cfg.HEARTS_SIZE.v));
        int cx = (int) Math.round(px);
        int cy = (int) Math.round(py);

        Gfx.alphaMul = 1f;
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(cx, cy);
        pose.scale((float) scale, (float) scale);
        int left = -cardW / 2, top = -cardH;
        Gfx.glow(g, left, top, cardW, cardH, 7, 0x000000, .40f, 4);
        Gfx.rr(g, left, top, cardW, cardH, 7, 0xD9151722);
        Gfx.ring(g, left, top, cardW, cardH, 7, 1, 0x6AFFFFFF);
        Gfx.strC(g, mc.font, name, 0, top + 4, 0xFFF3F5FA);

        int rowLeft = -rowWidth / 2;
        for (int i = 0; i < totalHearts; i++) {
            boolean filled = i < filledHearts;
            Gfx.str(g, mc.font, filled ? "❤" : "♡",
                    rowLeft + i * 10, top + 17, filled ? 0xFFFF6177 : 0xFF5B6070);
        }
        Gfx.str(g, mc.font, number, rowLeft + totalHearts * 10, top + 17, 0xFFC6D8EA);
        pose.popMatrix();
    }
}
