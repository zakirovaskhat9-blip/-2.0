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
 * The Nursultan Target ESP renders the approved image itself, not a new
 * approximated diamond polygon. Its black background was converted into
 * transparent pixels; the white alpha mask is tinted by the selected color.
 * The other Target ESP style keeps its original generated antialiased sprite.
 */
final class TargetEspSprite {
    private static final Logger LOGGER = LoggerFactory.getLogger("LT6/TargetESP");
    private static final int TEXTURE_SIZE = 384;
    private static final int DIAMOND_TEXTURE_SIZE = 256;
    private static final int DRAW_SIZE = 128;
    private static final Identifier CORE_ID =
            Identifier.fromNamespaceAndPath("lt6", "generated/target_esp_core");
    private static final Identifier BLOOM_ID =
            Identifier.fromNamespaceAndPath("lt6", "generated/target_esp_bloom");
    // Faithful alpha silhouette extracted from the user's supplied image.
    private static final Identifier DIAMOND_CORE =
            Identifier.fromNamespaceAndPath("lt6", "textures/gui/nursultan_target_esp.png");

    // Standard style: two strokes per chevron, top/right/bottom/left.
    private static final double[][] SEGMENTS = {
            {-13, -23,   0, -36}, {0, -36,   13, -23},
            {23, -13,   36,   0}, {36,   0,   23,  13},
            {-13, 23,    0,  36}, {0,  36,   13,  23},
            {-23,-13,  -36,   0}, {-36, 0,  -23,  13}
    };

    private static boolean initialized;
    private static boolean failed;

    private TargetEspSprite() { }

    static void render(GuiGraphics graphics, int argb, float opacity, double bloomPercent, int style) {
        float glow = (float) Math.max(0.0, Math.min(2.0, bloomPercent / 100.0));
        if (style == 1) {
            // At 0% glow a single unmodified silhouette is displayed.
            // Optional halo uses only scaled copies of this same image, never a
            // different generated shape. The core stays sharp at any setting.
            if (glow > 0.01f) {
                draw(graphics, DIAMOND_CORE,
                        Gfx.a(argb, Math.min(1f, opacity * glow * 0.16f)),
                        DRAW_SIZE + 14, DIAMOND_TEXTURE_SIZE);
                draw(graphics, DIAMOND_CORE,
                        Gfx.a(argb, Math.min(1f, opacity * glow * 0.25f)),
                        DRAW_SIZE + 7, DIAMOND_TEXTURE_SIZE);
            }
            draw(graphics, DIAMOND_CORE, Gfx.a(argb, opacity), DRAW_SIZE, DIAMOND_TEXTURE_SIZE);
            return;
        }

        if (!initialized && !failed) initialize();
        if (!initialized) return;
        if (glow > 0.01f) {
            draw(graphics, BLOOM_ID, Gfx.a(argb, Math.min(1f, opacity * glow)),
                    DRAW_SIZE, TEXTURE_SIZE);
            if (glow > 1.10f) {
                draw(graphics, BLOOM_ID,
                        Gfx.a(argb, Math.min(1f, opacity * (glow - 1.0f) * 0.6f)),
                        DRAW_SIZE, TEXTURE_SIZE);
            }
        }
        draw(graphics, CORE_ID, Gfx.a(argb, opacity), DRAW_SIZE, TEXTURE_SIZE);
    }

    private static void draw(GuiGraphics graphics, Identifier image, int color, int drawSize, int textureSize) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, image,
                -drawSize / 2, -drawSize / 2, 0f, 0f,
                drawSize, drawSize, textureSize, textureSize,
                textureSize, textureSize, color);
    }

    private static void initialize() {
        try {
            register(CORE_ID, makeImage(false), "LT6 Standard core");
            register(BLOOM_ID, makeImage(true), "LT6 Standard bloom");
            initialized = true;
        } catch (RuntimeException e) {
            failed = true;
            LOGGER.error("Failed to prepare antialiased Standard Target ESP textures", e);
        }
    }

    private static void register(Identifier id, NativeImage image, String description) {
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
                    alpha = 0.70 * Math.exp(-minDist2 / (2.0 * 5.0 * 5.0))
                            + 0.36 * Math.exp(-minDist2 / (2.0 * 11.0 * 11.0));
                } else {
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
