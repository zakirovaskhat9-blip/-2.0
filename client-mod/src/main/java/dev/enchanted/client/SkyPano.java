package dev.enchanted.client;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;

/** Cubemap bridge for the current Mojang-mapped 1.21.11 renderer. */
public final class SkyPano {
    private static final Map<String,Object> MAPS=new HashMap<>();
    private static Method draw;
    private static boolean failed;
    private SkyPano() {}
    public static boolean available() {return !failed;}
    public static boolean draw(Minecraft mc) {
        if (failed || !Cfg.SKY.enabled || mc.level==null) return false;
        try {
            SkyLib.Def def=SkyLib.current(Cfg.SKY_PRESET.idx);
            Object cube=MAPS.get(def.pano);
            if (cube==null) {
                Class<?> ident=Class.forName("net.minecraft.resources.Identifier");
                Object id=ident.getMethod("fromNamespaceAndPath",String.class,String.class)
                  .invoke(null,"enchanted_client","textures/gui/sky/"+def.pano);
                Class<?> renderer=Class.forName("net.minecraft.client.renderer.CubeMap");
                Constructor<?> constructor=renderer.getConstructor(ident);
                cube=constructor.newInstance(id);
                renderer.getMethod("registerTextures", Class.forName("net.minecraft.client.renderer.texture.TextureManager"))
                  .invoke(cube, mc.getTextureManager());
                draw=renderer.getMethod("render",Minecraft.class,float.class,float.class);
                MAPS.put(def.pano,cube);
            }
            if(draw==null) draw=cube.getClass().getMethod("render",Minecraft.class,float.class,float.class);
            var camera=mc.gameRenderer.getMainCamera();
            draw.invoke(cube,mc,camera.xRot(),camera.yRot());
            return true;
        } catch(Throwable error) {
            failed=true;
            org.slf4j.LoggerFactory.getLogger("LT6").warn("Custom Sky disabled: renderer not compatible",error);
            return false;
        }
    }
}
