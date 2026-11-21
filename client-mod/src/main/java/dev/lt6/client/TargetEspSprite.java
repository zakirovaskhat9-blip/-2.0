package dev.lt6.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Antialiased, rounded chevrons with a separate wide bloom layer.
 *
 * The sprite is generated once (384x384) on the client/render thread and then
 * tinted by GuiGraphics. No per-frame CPU geometry/pixel generation or blocky
 * rotated rectangles. Four arrows, no dot in the middle.
 */
final class TargetEspSprite {
    private static final Logger LOGGER = LoggerFactory.getLogger("LT6/TargetESP");
    private static final int TEXTURE_SIZE = 384;
    private static final int DRAW_SIZE = 128;
    private static final Identifier CORE_ID =
            Identifier.fromNamespaceAndPath("lt6", "generated/target_esp_core");
    private static final Identifier BLOOM_ID =
            Identifier.fromNamespaceAndPath("lt6", "generated/target_esp_bloom");
    private static final Identifier DIAMOND_CORE =
            Identifier.fromNamespaceAndPath("lt6", "generated/nursultan_core");
    private static final Identifier DIAMOND_BLOOM =
            Identifier.fromNamespaceAndPath("lt6", "generated/nursultan_bloom");

    // Two strokes per chevron: top, right, bottom, left (outward-facing).
    private static final double[][] SEGMENTS = {
            {-13, -23,   0, -36}, {0, -36,   13, -23},
            {23, -13,   36,   0}, {36,   0,   23,  13},
            {-13, 23,    0,  36}, {0,  36,   13,  23},
            {-23,-13,  -36,   0}, {-36, 0,  -23,  13}
    };

    // A symmetric, four-piece open square. The outer silhouette remains square;
    // each INNER tip has a short flat facet, making its angles obtuse, not 90°.
    // Stroke tapers gently toward the open ends; bloom stays independent.
    private static final double[][] NURSULTAN_TOP = {
            {-16.5, -26.0}, {-3.5, -39.0}, {-1.6, -41.5}, {1.6, -41.5},
            {3.5, -39.0}, {16.5, -26.0}, {17.2, -23.0}, {15.0, -19.8},
            {12.1, -19.1}, {9.2, -21.0}, {3.0, -27.0}, {-3.0, -27.0},
            {-9.2, -21.0}, {-12.1, -19.1}, {-15.0, -19.8}, {-17.2, -23.0}
    };

    private static boolean initialized;
    private static boolean failed;

    private TargetEspSprite() { }

    static void render(GuiGraphics graphics, int argb, float opacity, double bloomPercent, int style) {
        if (!initialized && !failed) initialize();
        if (!initialized) return;
        Identifier core = style == 1 ? DIAMOND_CORE : CORE_ID;
        Identifier bloom = style == 1 ? DIAMOND_BLOOM : BLOOM_ID;
        float glow = (float) Math.max(0.0, Math.min(2.0, bloomPercent / 100.0));
        if (glow > 0.01f) {
            // Outer radiance and core are separate, allowing a strong glow without
            // turning the whole arrow into an opaque, jagged rectangle.
            draw(graphics, bloom, Gfx.a(argb, Math.min(1f, opacity * glow)));
            if (glow > 1.10f) {
                draw(graphics, bloom, Gfx.a(argb, Math.min(1f, opacity * (glow - 1.0f) * 0.6f)));
            }
        }
        draw(graphics, core, Gfx.a(argb, opacity));
    }

    private static void draw(GuiGraphics graphics, Identifier image, int color) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, image,
                -DRAW_SIZE / 2, -DRAW_SIZE / 2, 0f, 0f,
                DRAW_SIZE, DRAW_SIZE, TEXTURE_SIZE, TEXTURE_SIZE,
                TEXTURE_SIZE, TEXTURE_SIZE, color);
    }

    private static void initialize() {
        try {
            register(CORE_ID, makeImage(false, false), "LT6 Standard core");
            register(BLOOM_ID, makeImage(true, false), "LT6 Standard bloom");
            register(DIAMOND_CORE, makeImage(false, true), "LT6 Nursultan obtuse inner corners core");
            register(DIAMOND_BLOOM, makeImage(true, true), "LT6 Nursultan bloom");
            initialized = true;
        } catch (RuntimeException e) {
            failed = true;
            LOGGER.error("Failed to prepare antialiased Target ESP textures", e);
        }
    }

    private static void register(Identifier id, NativeImage image, String description) {
        // DynamicTexture owns the NativeImage after construction.
        DynamicTexture texture = new DynamicTexture(() -> description, image);
        Minecraft.getInstance().getTextureManager().register(id, texture);
        texture.upload();
    }

    private static NativeImage makeImage(boolean bloom, boolean diamond) {
        NativeImage image = new NativeImage(TEXTURE_SIZE, TEXTURE_SIZE, true);
        double multiplier = DRAW_SIZE / (double) TEXTURE_SIZE;
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            double py = (y + 0.5) * multiplier - DRAW_SIZE / 2.0;
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                double px = (x + 0.5) * multiplier - DRAW_SIZE / 2.0;
                double alpha;
                if (diamond) {
                    // A smooth SDF of four identical 90-degree rotated shapes.
                    // The INNER silhouette is chamfered to an obtuse angle.
                    double signedDistance = nursultanDistance(px, py);
                    if (bloom) {
                        // This layer is invisible at 0% glow.
                        double outside = Math.max(0.0, signedDistance);
                        alpha = 0.65 * Math.exp(-outside * outside / (2.0 * 4.0 * 4.0))
                                + 0.22 * Math.exp(-outside * outside / (2.0 * 8.0 * 8.0));
                    } else {
                        double coverage = Math.max(0.0, Math.min(1.0, 0.5 - signedDistance));
                        alpha = coverage * coverage * (3.0 - 2.0 * coverage);
                    }
                } else {
                    // Preserve the Standard style exactly as before.
                    double minDist2 = Double.POSITIVE_INFINITY;
                    for (double[] segment : SEGMENTS) {
                        minDist2 = Math.min(minDist2, distSquaredToSegment(
                                px, py, segment[0], segment[1], segment[2], segment[3]));
                    }
                    if (bloom) {
                        alpha = 0.70 * Math.exp(-minDist2 / (2.0 * 5.0 * 5.0))
                                + 0.36 * Math.exp(-minDist2 / (2.0 * 11.0 * 11.0));
                    } else {
                        double d = Math.sqrt(minDist2);
                        double edge = Math.max(0.0, Math.min(1.0, (2.75 - d) / 1.3));
                        alpha = edge * edge * (3.0 - 2.0 * edge);
                    }
                }
                int a = (int) Math.round(255.0 * Math.max(0.0, Math.min(1.0, alpha)));
                image.setPixelABGR(x, y, (a << 24) | 0x00FFFFFF);
            }
        }
        return image;
    }

    private static double nursultanDistance(double x, double y) {
        double result = Double.POSITIVE_INFINITY;
        double lx = x;
        double ly = y;
        for (int turn = 0; turn < 4; turn++) {
            // Skip far-away parts to reduce one-time texture generation cost.
            if (Math.abs(lx) < 21.0 && ly > -45.0 && ly < -15.0) {
                result = Math.min(result, polygonDistance(lx, ly, NURSULTAN_TOP));
            }
            double tmp = lx;
            lx = -ly;
            ly = tmp;
        }
        // A small rounded offset softens all corners without enlarging the ESP.
        return result - 0.5;
    }

    private static double polygonDistance(double x, double y, double[][] points) {
        double shortest = Double.POSITIVE_INFINITY;
        boolean inside = false;
        for (int i = 0, j = points.length - 1; i < points.length; j = i++) {
            double ax = points[j][0], ay = points[j][1];
            double bx = points[i][0], by = points[i][1];
            shortest = Math.min(shortest, distSquaredToSegment(x, y, ax, ay, bx, by));
            if ((ay > y) != (by > y)
                    && x < (bx - ax) * (y - ay) / (by - ay) + ax) {
                inside = !inside;
            }
        }
        double distance = Math.sqrt(shortest);
        return inside ? -distance : distance;
    }

    private static double distSquaredToSegment(double x, double y,
                                                double x0, double y0,
                                                double x1, double y1) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double t = ((x - x0) * dx + (y - y0) * dy) / (dx * dx + dy * dy);
        t = Math.max(0.0, Math.min(1.0, t));
        double ex = x - (x0 + t * dx);
        double ey = y - (y0 + t * dy);
        return ex * ex + ey * ey;
    }
}
