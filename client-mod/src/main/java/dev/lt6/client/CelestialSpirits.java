package dev.lt6.client;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.Locale;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Celestial spirits: a world-anchored, camera-projected hologram.
 *
 * Renders after the world as a HUD overlay, so markers are readable through
 * walls without changing Minecraft's global depth state or shadow pipeline.
 * Projected geometry follows partial-tick interpolation already done by Hud.
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

    static void render(GuiGraphics g, Camera camera, LivingEntity target,
                       double worldX, double worldY, double worldZ, double entityHeight,
                       int sw, int sh, double fov, float opacity, float spawn,
                       float impact, double overallSize, double glowStrength) {
        if (!textureReady && !textureFailed) initSprite();
        setup(camera, sw, sh, fov);
        double time = System.nanoTime() * 1e-9;
        double headY = worldY + entityHeight * (target instanceof ArmorStand ? 0.80 : 0.72);
        double size = Math.max(0.40, Math.min(1.8, overallSize));
        double half = 0.30 * size;
        float visible = Math.max(0f, Math.min(1f, opacity));
        Minecraft mc = Minecraft.getInstance();

        // Line of sight attenuates hidden spirits instead of disabling the ESP.
        if (mc.level != null && mc.player != null) {
            try {
                var hit = mc.level.clip(new ClipContext(
                        camera.position(), new Vec3(worldX, headY, worldZ),
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
                if (hit.getType() == HitResult.Type.BLOCK) visible *= 0.80f;
            } catch (RuntimeException ignored) {
                // Keep the marker visible if the camera temporarily leaves a world.
            }
        }

        // A target change eases the spirit into existence with a short overshoot.
        float t = Math.max(0f, Math.min(1f, spawn));
        double appear = 1.0 + 1.70158;
        double eased = 1.0 + appear * Math.pow(t - 1.0, 3)
                + 1.70158 * Math.pow(t - 1.0, 2);
        double animatedSize = Math.max(0.01, eased) * size;
        Point center = project(worldX, headY, worldZ);
        if (center == null) return;

        // A projected 0.6m head box, with holographic faces and 12 fine edges.
        Point[] corners = new Point[8];
        for (int v = 0; v < 8; v++) {
            double x = worldX + ((v & 1) != 0 ? half : -half);
            double z = worldZ + ((v & 2) != 0 ? half : -half);
            double y = headY + ((v & 4) != 0 ? half : -half);
            corners[v] = project(x, y, z);
        }
        drawBox(g, corners, time, visible, glowStrength);

        // Soft tracer begins outside the crosshair and becomes brighter near target.
        tracer(g, center, time, visible);

        // Radar ring underneath the orbit, in the horizontal world XZ plane.
        double ringY = headY - 0.54 * animatedSize;
        drawRing(g, worldX, ringY, worldZ, time, animatedSize, visible);

        // Four independent orbit planes, opposing directions and 18-piece trails.
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
                double radius = screenScale * (trail == 0 ? 1.0 : (0.16 + 0.8 * tail));
                double glow = Math.max(0.25, Math.min(2.0, glowStrength / 100.0));
                drawGlow(g, p.x, p.y, radius * (0.85 + glow * 0.45), color, alpha);
                if (trail == 0) {
                    // Bright white point in a colored aura, like a tiny comet.
                    drawGlow(g, p.x, p.y, radius * 0.28, 0xFFFFFFFF, alpha);
                }
            }
        }
        drawTag(g, target, worldX, headY + half + 0.28, worldZ, time, visible, impact);
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

    private static void drawBox(GuiGraphics g, Point[] c, double time,
                                float alpha, double glow) {
        for (Point p:c) if(p == null) return;
        double pulse = 0.5 + 0.5 * Math.sin(time * 1.2);
        int color = Gfx.mix(CYAN,VIOLET,(float)pulse);
        // Bottom/top square indexing: (0,1,3,2), (4,5,7,6).
        int[][] faces = {{0,1,5,4},{1,3,7,5},{3,2,6,7},{2,0,4,6}};
        for (int[] f: faces) {
            Point leftBottom=c[f[0]], rightBottom=c[f[1]];
            Point rightTop=c[f[2]], leftTop=c[f[3]];
            for (int i=0;i<22;i++) {
                double k=i/22.0;
                Point a=between(leftBottom,leftTop,k);
                Point b=between(rightBottom,rightTop,k);
                int fill=Gfx.a(color,alpha*(float)(0.20*(1-k)));
                line(g,a.x,a.y,b.x,b.y,1.0,fill);
            }
        }
        int[][] edges = {
                {0,1},{1,3},{3,2},{2,0},
                {4,5},{5,7},{7,6},{6,4},
                {0,4},{1,5},{2,6},{3,7}
        };
        for (int[] e:edges) {
            Point a=c[e[0]], b=c[e[1]];
            if (glow > 0) line(g,a.x,a.y,b.x,b.y,3.8,
                    Gfx.a(color,alpha*0.18f*(float)Math.min(2,glow/100.0)));
            line(g,a.x,a.y,b.x,b.y,1.5,Gfx.a(color,alpha*0.91f));
        }
        for(Point p:c) drawGlow(g,p.x,p.y,3.2,color,alpha*0.45f);
    }

    private static Point between(Point a,Point b,double t) {
        return new Point(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t,
                a.depth+(b.depth-a.depth)*t);
    }

    private static void tracer(GuiGraphics g,Point target,double time,float opacity) {
        double sx=width*0.5, sy=height*0.5;
        double dx=target.x-sx,dy=target.y-sy;
        double dist=Math.hypot(dx,dy);
        if (dist<22) return;
        sx += dx/dist*19.0; sy += dy/dist*19.0;
        for (int i=0;i<20;i++){
            double a=i/20.0,b=(i+1)/20.0;
            int tint=Gfx.mix(CYAN,VIOLET,(float)(0.5+0.5*Math.sin(time*.65+a*1.9)));
            line(g,sx+(target.x-sx)*a,sy+(target.y-sy)*a,
                    sx+(target.x-sx)*b,sy+(target.y-sy)*b,
                    1.0,Gfx.a(tint,opacity*(float)(.1+.75*b*b)));
        }
    }

    private static void drawRing(GuiGraphics g,double x,double y,double z,
                                 double time,double scale,float opacity){
        double ringRadius=0.67*scale*(1+0.06*Math.sin(time*2.2));
        int steps=60;
        for(int i=0;i<steps;i++){
            double a=TWO_PI*i/steps+time*.52,b=TWO_PI*(i+1)/steps+time*.52;
            Point pa=project(x+ringRadius*Math.cos(a),y,z+ringRadius*Math.sin(a));
            Point pb=project(x+ringRadius*Math.cos(b),y,z+ringRadius*Math.sin(b));
            if(pa==null||pb==null)continue;
            int tint=Gfx.mix(CYAN,VIOLET,(float)i/steps);
            line(g,pa.x,pa.y,pb.x,pb.y,2.0,Gfx.a(tint,opacity*.65f));
            if(i%5==0){
                Point tick=project(x+(ringRadius+.09*scale)*Math.cos(a),y,
                        z+(ringRadius+.09*scale)*Math.sin(a));
                if(tick!=null)line(g,pa.x,pa.y,tick.x,tick.y,1.5,
                        Gfx.a(tint,opacity*.72f));
            }
        }
    }

    private static void drawTag(GuiGraphics g,LivingEntity target,double x,double y,double z,
                                double time,float alpha,float impact) {
        Point p=project(x,y,z);
        if(p==null||p.x<-100||p.x>width+100||p.y<-100||p.y>height+100) return;
        Minecraft mc=Minecraft.getInstance();
        Font font=mc.font;
        String name=target instanceof ArmorStand ? "Дух" : target.getName().getString();
        if(name.length()>18)name=name.substring(0,18);
        double dist=mc.player==null?0:mc.player.position().distanceTo(new Vec3(x,y,z));
        String tag=name+" • "+String.format(Locale.ROOT,"%.1fm",dist);
        int w=font.width(tag)+28,h=19;
        int bx=(int)Math.round(p.x-w/2.0),by=(int)Math.round(p.y-h/2.0);
        Gfx.rr(g,bx,by,w,h,4,Gfx.a(0xFF0F0F16,alpha*.75f));
        Gfx.ring(g,bx,by,w,h,4,1,Gfx.a(
                Gfx.mix(CYAN,VIOLET,(float)(.5+.5*Math.sin(time))),alpha*.85f));
        int status=Gfx.a(impact>0.1?0xFFFF4652:CYAN,
                alpha*(float)(.70+.30*Math.sin(time*4)));
        g.fill(bx+8,by+8,bx+12,by+12,status);
        g.drawString(font,tag,bx+18,by+5,Gfx.a(0xFFF6F9FF,alpha));
    }

    private static void line(GuiGraphics g,double x0,double y0,double x1,double y1,
                             double thickness,int color){
        double dx=x1-x0,dy=y1-y0;
        double len=Math.hypot(dx,dy);
        if(len<.01)return;
        var pose=g.pose();
        pose.pushMatrix();
        pose.translate((float)x0,(float)y0);
        pose.rotate((float)Math.atan2(dy,dx));
        int top=(int)Math.floor(-thickness/2.0);
        int bottom=(int)Math.ceil(thickness/2.0);
        g.fill(0,top,(int)Math.ceil(len),bottom,color);
        pose.popMatrix();
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
