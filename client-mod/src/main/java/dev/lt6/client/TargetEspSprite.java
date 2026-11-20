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

    // Two strokes per chevron: top, right, bottom, left (outward-facing).
    private static final double[][] SEGMENTS = {
            {-13, -23,   0, -36}, {0, -36,   13, -23},
            {23, -13,   36,   0}, {36,   0,   23,  13},
            {-13, 23,    0,  36}, {0,  36,   13,  23},
            {-23,-13,  -36,   0}, {-36, 0,  -23,  13}
    };

    private static boolean initialized;
    private static boolean failed;

    private TargetEspSprite() { }

    static void render(GuiGraphics graphics, int argb, float opacity, double bloomPercent) {
        if (!initialized && !failed) initialize();
        if (!initialized) return;
        float glow = (float) Math.max(0.0, Math.min(2.0, bloomPercent / 100.0));
        if (glow > 0.01f) {
            // Outer radiance and core are separate, allowing a strong glow without
            // turning the whole arrow into an opaque, jagged rectangle.
            draw(graphics, BLOOM_ID, Gfx.a(argb, Math.min(1f, opacity * glow)));
            if (glow > 1.10f) {
                draw(graphics, BLOOM_ID, Gfx.a(argb, Math.min(1f, opacity * (glow - 1.0f) * 0.6f)));
            }
        }
        draw(graphics, CORE_ID, Gfx.a(argb, opacity));
    }

    private static void draw(GuiGraphics graphics, Identifier image, int color) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, image,
                -DRAW_SIZE / 2, -DRAW_SIZE / 2, 0f, 0f,
                DRAW_SIZE, DRAW_SIZE, TEXTURE_SIZE, TEXTURE_SIZE,
                TEXTURE_SIZE, TEXTURE_SIZE, color);
    }

    private static void initialize() {
        try {
            register(CORE_ID, makeImage(false), "LT6 Target ESP core");
            register(BLOOM_ID, makeImage(true), "LT6 Target ESP bloom");
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

    private static NativeImage makeImage(boolean bloom) {
        NativeImage image = new NativeImage(TEXTURE_SIZE, TEXTURE_SIZE, true);
        double multiplier = DRAW_SIZE / (double) TEXTURE_SIZE;
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            double py = (y + 0.5) * multiplier - DRAW_SIZE / 2.0;
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                double px = (x + 0.5) * multiplier - DRAW_SIZE / 2.0;
                double minDist2 = Double.POSITIVE_INFINITY;
                for (double[] segment : SEGMENTS) {
                    minDist2 = Math.min(minDist2, distSquaredToSegment(
                            px, py, segment[0], segment[1], segment[2], segment[3]));
                }

                double alpha;
                if (bloom) {
                    // Broad Gaussian halo: strong near the stroke, feathered out
                    // at both tips and corners with a fully transparent background.
                    alpha = 0.70 * Math.exp(-minDist2 / (2.0 * 5.0 * 5.0))
                            + 0.36 * Math.exp(-minDist2 / (2.0 * 11.0 * 11.0));
                } else {
                    // Smooth capsule distance field. Rounded segment caps and tip
                    // joins, with subpixel coverage for much cleaner diagonals.
                    double d = Math.sqrt(minDist2);
                    double edge = Math.max(0.0, Math.min(1.0, (2.75 - d) / 1.3));
                    alpha = edge * edge * (3.0 - 2.0 * edge);
                }
                int a = (int) Math.round(255.0 * Math.max(0.0, Math.min(1.0, alpha)));
                image.setPixelABGR(x, y, (a << 24) | 0x00FFFFFF);
            }
        }
        return image;
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
