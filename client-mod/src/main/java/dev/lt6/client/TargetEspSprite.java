package dev.lt6.client;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
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
    private static final Identifier DIAMOND_ORIGINAL =
            Identifier.fromNamespaceAndPath("lt6", "textures/gui/nursultan_target_esp.png");
    private static final Identifier DIAMOND_CORE =
            Identifier.fromNamespaceAndPath("lt6", "generated/target_esp_nursultan_symmetric");
    private static boolean diamondReady;
    private static final Identifier PAINTED_STANDARD =
            Identifier.fromNamespaceAndPath("lt6", "generated/target_esp_painted_standard");
    private static final Identifier PAINTED_DIAMOND =
            Identifier.fromNamespaceAndPath("lt6", "generated/target_esp_painted_nursultan");
    private static long paintKey = Long.MIN_VALUE;
    private static boolean paintReady;

    // Standard style: two strokes per chevron, top/right/bottom/left.
    private static final double[][] SEGMENTS = {
            {-14, -22,    0, -36}, { 0, -36,  14, -22},
            { 22, -14,   36,   0}, {36,   0,  22,  14},
            {-14,  22,    0,  36}, { 0,  36,  14,  22},
            {-22, -14,  -36,   0}, {-36, 0, -22,  14}
    };

    private static boolean initialized;
    private static boolean failed;

    private TargetEspSprite() { }

    static void render(GuiGraphics graphics, int primary, int secondary, float opacity,
                       double bloomPercent, int style, int paintMode) {
        float glow = (float)Math.max(0.0, Math.min(2.0, bloomPercent / 100.0));
        if (!initialized && !failed) initialize();

        Identifier painted = null;
        if (paintMode != 0 && initialized) {
            painted = paintedTexture(style, primary, secondary, paintMode);
        }
        boolean dual = painted != null;
        int color = dual ? 0xFFFFFFFF : primary;

        if (style == 1) {
            Identifier texture = dual ? painted : diamondReady ? DIAMOND_CORE : DIAMOND_ORIGINAL;
            if (glow > 0.01f) {
                draw(graphics, texture, Gfx.a(color, Math.min(1f, opacity * glow * 0.16f)),
                        DRAW_SIZE + 14, DIAMOND_TEXTURE_SIZE);
                draw(graphics, texture, Gfx.a(color, Math.min(1f, opacity * glow * 0.25f)),
                        DRAW_SIZE + 7, DIAMOND_TEXTURE_SIZE);
            }
            draw(graphics, texture, Gfx.a(color, opacity), DRAW_SIZE, DIAMOND_TEXTURE_SIZE);
            return;
        }

        if (!initialized) return;
        if (glow > 0.01f) {
            draw(graphics, BLOOM_ID, Gfx.a(primary, Math.min(1f, opacity * glow)),
                    DRAW_SIZE, TEXTURE_SIZE);
            if (glow > 1.10f)
                draw(graphics, BLOOM_ID,
                        Gfx.a(primary, Math.min(1f, opacity * (glow - 1f) * .6f)),
                        DRAW_SIZE, TEXTURE_SIZE);
        }
        draw(graphics, dual ? painted : CORE_ID, Gfx.a(color, opacity),
                DRAW_SIZE, TEXTURE_SIZE);
    }

    /**
     * Rasterize the approved alpha mask; only its fill colour changes.
     * Cache the uploaded GPU texture. Animated shimmer updates at most 4 Hz.
     */
    private static Identifier paintedTexture(int style, int primary, int secondary, int mode) {
        long phase = mode == 4 ? System.currentTimeMillis() / 250L : 0L;
        long key = ((long)style << 61) ^ ((long)mode << 56)
                ^ ((long)(primary & 0xFFFFFF) << 24) ^ (secondary & 0xFFFFFF)
                ^ (phase * 0x9E3779B97F4A7C15L);
        Identifier texture = style == 1 ? PAINTED_DIAMOND : PAINTED_STANDARD;
        if (paintReady && key == paintKey) return texture;
        try (NativeImage mask = style == 1 ? symmetricDiamond() : makeImage(false)) {
            int w = mask.getWidth(), h = mask.getHeight();
            NativeImage image = new NativeImage(w, h, true);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int alpha = (mask.getPixel(x, y) >>> 24) & 255;
                    int rgb;
                    switch (mode) {
                        case 1 -> rgb = x < w / 2 ? primary : secondary;
                        case 2 -> rgb = ((x < w / 2) == (y < h / 2)) ? primary : secondary;
                        case 3 -> rgb = Gfx.mix(primary, secondary, x / (float)Math.max(1, w - 1));
                        case 4 -> {
                            double theta = ((x + y) / (double)w) * 5.0 - phase * .38;
                            rgb = Gfx.mix(primary, secondary, (float)(.5 + .5 * Math.sin(theta)));
                        }
                        default -> rgb = primary;
                    }
                    image.setPixelABGR(x, y, (alpha << 24) | (rgb & 0xFFFFFF));
                }
            }
            register(texture, image, "LT6 dual-colour target ESP");
            paintKey = key;
            paintReady = true;
            return texture;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Cannot update dual-colour Target ESP; falling back to the original", e);
            paintReady = false;
            return null;
        }
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
            try {
                register(DIAMOND_CORE, symmetricDiamond(), "LT6 symmetric Nursultan diamond");
                diamondReady = true;
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Using original Nursultan texture until its symmetric version is available", e);
            }
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

    /**
     * Four-way rotational symmetry from the player's approved texture. Preserve
     * antialiased edges; never redraw a new geometric diamond over the image.
     */
    private static NativeImage symmetricDiamond() throws IOException {
        try (InputStream stream = TargetEspSprite.class.getResourceAsStream(
                "/assets/lt6/textures/gui/nursultan_target_esp.png")) {
            if (stream == null) throw new IOException("Nursultan texture not found");
            try (NativeImage original = NativeImage.read(stream)) {
                int w = original.getWidth(), h = original.getHeight();
                if (w != h) throw new IOException("Nursultan sprite must be square");
                NativeImage out = new NativeImage(w, h, true);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int a1 = (original.getPixel(x, y) >>> 24) & 255;
                        int a2 = (original.getPixel(w - 1 - y, x) >>> 24) & 255;
                        int a3 = (original.getPixel(w - 1 - x, h - 1 - y) >>> 24) & 255;
                        int a4 = (original.getPixel(y, h - 1 - x) >>> 24) & 255;
                        int a = (a1 + a2 + a3 + a4 + 2) / 4;
                        out.setPixelABGR(x, y, (a << 24) | 0x00FFFFFF);
                    }
                }
                return out;
            }
        }
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
