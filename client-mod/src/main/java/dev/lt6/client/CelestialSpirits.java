package dev.lt6.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Celestial spirits: four orbiting glowing spheres with enlarged fading trails.
 * Projected world positions follow the target without extra boxes or overlays.
 */
final class CelestialSpirits {
    private static final Logger LOGGER = LoggerFactory.getLogger("LT6/Celestial");
    private static final int CYAN = 0xFF00F0FF;
    private static final int VIOLET = 0xFF9D00FF;
    private static final int SPRITE_SIZE = 96;
    private static final Identifier GHOST_SPRITE =
            Identifier.fromNamespaceAndPath("lt6", "generated/celestial_ghost");
    private static final double TWO_PI = Math.PI * 2.0;
    private static boolean textureReady;
    private static boolean textureFailed;

    private static double camX, camY, camZ;
    private static double forwardX, forwardY, forwardZ, rightX, rightZ, upX, upY, upZ;
    private static double tanHalfFov, aspect;
    private static int width, height;

    private record Point(double x, double y, double depth) {}

    private CelestialSpirits() {}

    static void render(GuiGraphics g, Camera camera,
                       double worldX, double worldY, double worldZ, double entityHeight,
                       int sw, int sh, double fov, float opacity, float spawn,
                       double overallSize, double glowStrength) {
        if (!textureReady && !textureFailed) initSprite();
        setup(camera, sw, sh, fov);
        double time = System.nanoTime() * 1e-9;
        double headY = worldY + entityHeight * 0.72;
        double size = Math.max(0.40, Math.min(1.8, overallSize));
        float visible = Math.max(0f, Math.min(1f, opacity));
        Minecraft mc = Minecraft.getInstance();

        float visible = Math.max(0f, Math.min(1f, opacity));

        // A target change eases the spirit into existence with a short overshoot.
        float t = Math.max(0f, Math.min(1f, spawn));
        double appear = 1.0 + 1.70158;
        double eased = 1.0 + appear * Math.pow(t - 1.0, 3)
                + 1.70158 * Math.pow(t - 1.0, 2);
        double animatedSize = Math.max(0.01, eased) * size;
        if (project(worldX, headY, worldZ) == null) return;

        // Four large glowing spheres orbit the target with matching oversized trails.
        for (int ghost = 0; ghost < 4; ghost++) {
            double phase = TWO_PI * ghost / 4.0;
            int direction = (ghost & 1) == 0 ? 1 : -1;
            for (int trail = 17; trail >= 0; trail--) {
                double past = time - trail * 0.055;
                double theta = direction * (past * (1.70 + ghost * 0.17)) + phase;
                double tilt = Math.sin(past * 0.42 + phase) * 0.42;
                double orbitRadius = 0.76 * animatedSize;
                double ox = Math.cos(theta) * orbitRadius;
                double oz = Math.sin(theta) * orbitRadius;
                double x = worldX + ox * Math.cos(tilt) - oz * Math.sin(tilt);
                double z = worldZ + ox * Math.sin(tilt) + oz * Math.cos(tilt);
                double y = headY + Math.sin(past * 1.16 + phase) * 0.38 * animatedSize
                        + 0.12 * Math.sin(theta * 1.25 + phase) * animatedSize;
                Point p = project(x, y, z);
                if (p == null) continue;
                float tail = 1.0f - trail / 18.0f;
                float alpha = visible * tail * tail;
                int color = Gfx.mix(CYAN, VIOLET,
                        (float)(0.5 + 0.5 * Math.sin(time * 0.8 + phase + trail * 0.13)));
                double screenScale = Math.min(33.0, Math.max(5.0,
                        sh * 0.040 * animatedSize / Math.max(1.5, p.depth)));
                double radius = screenScale * 2.0 * (trail == 0 ? 1.0 : (0.16 + 0.8 * tail));
                double glow = Math.max(0.25, Math.min(2.0, glowStrength / 100.0));
                drawGlow(g, p.x, p.y, radius * (0.85 + glow * 0.45), color, alpha);
                if (trail == 0) {
                    // White core gives each round spirit a distinct spherical center.
                    drawGlow(g, p.x, p.y, radius * 0.34, 0xFFFFFFFF, alpha);
                }
            }
        }
    }

    private static void setup(Camera camera, int sw, int sh, double fov) {
        width = sw; height = sh;
        camX = camera.position().x;
        camY = camera.position().y;
        camZ = camera.position().z;
        double yaw = Math.toRadians(camera.yRot());
        double pitch = Math.toRadians(camera.xRot());
        double cosPitch = Math.cos(pitch);
        double sinYaw = Math.sin(yaw), cosYaw = Math.cos(yaw);
        forwardX = -sinYaw * cosPitch;
        forwardY = -Math.sin(pitch);
        forwardZ = cosYaw * cosPitch;
        rightX = -cosYaw;
        rightZ = -sinYaw;
        upX = -rightZ * forwardY;
        upY = rightZ * forwardX - rightX * forwardZ;
        upZ = rightX * forwardY;
        tanHalfFov = Math.max(0.01, Math.tan(Math.toRadians(fov) / 2.0));
        aspect = (double)sw / Math.max(1, sh);
    }

    private static Point project(double x, double y, double z) {
        double dx=x-camX, dy=y-camY, dz=z-camZ;
        double depth = dx*forwardX + dy*forwardY + dz*forwardZ;
        if (depth <= 0.08) return null;
        double sx = (0.5 + 0.5*(dx*rightX + dz*rightZ)/(depth*tanHalfFov*aspect)) * width;
        double sy = (0.5 - 0.5*(dx*upX + dy*upY + dz*upZ)/(depth*tanHalfFov)) * height;
        if (!Double.isFinite(sx) || !Double.isFinite(sy)) return null;
        return new Point(sx,sy,depth);
    }

    private static void drawGlow(GuiGraphics g,double x,double y,double radius,
                                 int color,float alpha) {
        if (!textureReady || radius < .2 || alpha < .006) return;
        int size=(int)Math.max(2,Math.min(130,Math.round(radius*2)));
        g.blit(RenderPipelines.GUI_TEXTURED,GHOST_SPRITE,
                (int)Math.round(x-size/2.0),(int)Math.round(y-size/2.0),
                0f,0f,size,size,SPRITE_SIZE,SPRITE_SIZE,
                SPRITE_SIZE,SPRITE_SIZE,Gfx.a(color,alpha));
    }

    private static void initSprite(){
        try {
            NativeImage texture=new NativeImage(SPRITE_SIZE,SPRITE_SIZE,true);
            for(int y=0;y<SPRITE_SIZE;y++){
                for(int x=0;x<SPRITE_SIZE;x++){
                    double dx=(x+.5-SPRITE_SIZE/2.0)/(SPRITE_SIZE/2.0);
                    double dy=(y+.5-SPRITE_SIZE/2.0)/(SPRITE_SIZE/2.0);
                    double r2=dx*dx+dy*dy;
                    double a=0.82*Math.exp(-r2*5.0)+0.44*Math.exp(-r2*15.0);
                    int al=(int)Math.round(255*Math.max(0,Math.min(1,a)));
                    texture.setPixelABGR(x,y,(al<<24)|0x00FFFFFF);
                }
            }
            DynamicTexture sprite=new DynamicTexture(()->"LT6 Celestial ghosts",texture);
            Minecraft.getInstance().getTextureManager().register(GHOST_SPRITE,sprite);
            sprite.upload();
            textureReady=true;
        }catch(RuntimeException e){
            textureFailed=true;
            LOGGER.error("Could not create Celestial ghost texture",e);
        }
    }
}
