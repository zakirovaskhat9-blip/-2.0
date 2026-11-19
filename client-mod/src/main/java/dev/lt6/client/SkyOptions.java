package dev.lt6.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.CloudStatus;

public final class SkyOptions {
    private static CloudStatus saved;
    private SkyOptions() {}
    public static void tick(Minecraft mc) {
        var option=mc.options.cloudStatus();
        if(Cfg.SKY.enabled) {
            if(saved==null) saved=option.get();
            if(option.get()!=CloudStatus.OFF) option.set(CloudStatus.OFF);
        } else if(saved!=null) {option.set(saved); saved=null;}
    }
}
