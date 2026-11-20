package dev.lt6.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Switch LT6 skybox resource packs using Minecraft's standard repository. */
public final class SkyPacks {
    private static final Logger LOGGER = LoggerFactory.getLogger("LT6/SkyPacks");
    private static final String[] FILES = {
        "LT6_Clouds.zip", "LT6_Green.zip", "LT6_Teal.zip",
        "LT6_Akame.zip", "LT6_Violet.zip", "LT6_BlueMoon.zip"
    };
    private static boolean initialized;
    private SkyPacks() { }

    public static void tick(Minecraft mc) {
        if (initialized || mc.player == null || mc.level == null) return;
        initialized = true;
        if (Cfg.SKY.enabled) select(Cfg.SKY_PRESET.idx, true);
    }

    public static void select(int index, boolean enabled) {
        Minecraft mc = Minecraft.getInstance();
        try {
            PackRepository repo = mc.getResourcePackRepository();
            repo.reload();
            Collection<String> available = repo.getAvailableIds();
            List<String> wanted = new ArrayList<>(repo.getSelectedIds());
            wanted.removeIf(id -> id.startsWith("file/LT6_") && id.endsWith(".zip"));
            if (enabled) {
                String file = FILES[Math.floorMod(index, FILES.length)];
                String id = "file/" + file;
                if (!available.contains(id)) {
                    LOGGER.warn("LT6 sky {} not installed in resourcepacks. Run the launcher first.", file);
                    Cfg.SKY.enabled = false;
                    return;
                }
                wanted.add(id); // highest-priority pack
            }
            repo.setSelected(wanted);
            mc.options.updateResourcePacks(repo);
            mc.reloadResourcePacks().whenComplete((v, error) -> {
                if (error != null) LOGGER.error("LT6 sky pack reload failed", error);
                else LOGGER.info("LT6 sky resource pack: {}", enabled ? FILES[Math.floorMod(index, FILES.length)] : "disabled");
            });
        } catch (RuntimeException error) {
            LOGGER.error("Cannot switch LT6 sky pack", error);
            Cfg.SKY.enabled = false;
        }
    }
}
