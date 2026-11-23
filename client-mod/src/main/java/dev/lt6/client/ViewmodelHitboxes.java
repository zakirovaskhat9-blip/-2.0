package dev.lt6.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Actual screen-space bounds from first-person model matrices. The old chat
 * editor used hard-coded fractions (0.265/0.39) that stopped matching after
 * moving or scaling an arm or an item.
 *
 * A hitbox is captured immediately before the real model draw, after vanilla
 * swing/equip pose and LT6's *independent* transform have been applied. The
 * four targets never share a transform or a hitbox.
 */
public final class ViewmodelHitboxes {
    public record Rect(double x, double y, double width, double height) {
        public boolean contains(double px, double py) {
            return px >= x && py >= y && px < x + width && py < y + height;
        }
        public double cx() { return x + width * .5; }
        public double cy() { return y + height * .5; }
    }

    private static final Rect[] boxes = new Rect[4];
    private static final long[] updated = new long[4];
    private ViewmodelHitboxes() { }

    // Indices: main hand, off hand, main item, off item.
    private static int index(boolean main, boolean item) {
        return (item ? 2 : 0) + (main ? 0 : 1);
    }

    public static void capture(PoseStack pose, boolean main, boolean item) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        if (w <= 1 || h <= 1) return;

        // The hand model is about 4x12x4 model pixels. Held items typically
        // occupy a 16x16x16 cube; use a little padding for animated geometry.
        float rx = item ? .48f : .34f;
        float ry = item ? .48f : .57f;
        float rz = item ? .48f : .33f;

        try {
            Matrix4f model = new Matrix4f(RenderSystem.getModelViewMatrix())
                    .mul(pose.last().pose());
            float fov = ((Number)mc.options.fov().get()).floatValue();
            Matrix4f clip = mc.gameRenderer.getProjectionMatrix(fov).mul(model);
            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            int projected = 0;
            for (int xi = -1; xi <= 1; xi += 2)
                for (int yi = -1; yi <= 1; yi += 2)
                    for (int zi = -1; zi <= 1; zi += 2) {
                        Vector4f point = new Vector4f(xi * rx, yi * ry, zi * rz, 1.0f).mul(clip);
                        if (!Float.isFinite(point.w) || point.w <= .015f) continue;
                        double px = (1 + point.x / point.w) * w * .5;
                        double py = (1 - point.y / point.w) * h * .5;
                        if (!Double.isFinite(px) || !Double.isFinite(py)) continue;
                        minX = Math.min(minX, px);
                        maxX = Math.max(maxX, px);
                        minY = Math.min(minY, py);
                        maxY = Math.max(maxY, py);
                        projected++;
                    }

            if (projected < 4 || maxX < 0 || minX > w || maxY < 0 || minY > h)
                return;
            // Clamp wild FOV/perspective corners; don't make invisible controls
            // intercept the entire chat or the other hand.
            double cx = Math.max(0, Math.min(w, (minX + maxX) * .5));
            double cy = Math.max(0, Math.min(h, (minY + maxY) * .5));
            double bw = Math.max(28, Math.min(w * .27, maxX - minX));
            double bh = Math.max(34, Math.min(h * .44, maxY - minY));
            int k = index(main, item);
            boxes[k] = new Rect(cx - bw * .5, cy - bh * .5, bw, bh);
            updated[k] = System.nanoTime();
        } catch (RuntimeException ex) {
            // Render/graphics state can be momentarily unavailable on reload;
            // a missed frame must not crash the game.
        }
    }

    public static Rect get(boolean main, boolean item) {
        int k = index(main, item);
        if (System.nanoTime() - updated[k] > 600_000_000L)
            return null;
        return boxes[k];
    }

    public static void clear() {
        java.util.Arrays.fill(boxes, null);
        java.util.Arrays.fill(updated, 0);
    }
}
