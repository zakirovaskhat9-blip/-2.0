package dev.enchanted.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** Client-side skin fallback and nickname skin changer. Never sends changes to servers. */
public final class Skins {
    private static final Pattern NICK = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final long OK_TTL = 300_000L, MISS_TTL = 45_000L;
    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> BUSY = ConcurrentHashMap.newKeySet();
    private static final ExecutorService IO = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "enchanted-skin-io");
        thread.setDaemon(true);
        return thread;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(5)).build();
    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("enchanted-skins.json");
    private static final Path DISK = FabricLoader.getInstance().getConfigDir().resolve("enchanted-skin-cache");

    private static final class Entry {
        volatile Identifier texture;
        volatile boolean slim;
        volatile long checked;
        volatile boolean found;
        PlayerSkin original, replacement; // Client/render thread only.
    }
    private record Download(byte[] bytes, boolean slim) { }

    private static String server = "", accountNick = "", selectedNick = "";
    private static Path localFile;
    private static boolean localSlim;
    public static volatile boolean active = true;
    private Skins() { }

    public static void init() {
        try {
            if (Files.isRegularFile(CONFIG)) {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG)).getAsJsonObject();
                server = string(o, "server").replaceAll("/+$", "");
                // Only use HTTPS for public URLs; HTTP allowed for local development servers.
                if (!(server.startsWith("https://") || server.startsWith("http://localhost:") || server.startsWith("http://127.0.0.1:"))) server = "";
                accountNick = string(o, "nick");
                selectedNick = string(o, "selectedNick");
                if (!NICK.matcher(selectedNick).matches()) selectedNick = "";
                localSlim = o.has("slim") && o.get("slim").getAsBoolean();
                if (!string(o, "file").isBlank()) localFile = Path.of(string(o, "file"));
            }
            Files.createDirectories(DISK);
        } catch (Exception exception) {
            System.err.println("[LT6 Skins] Config error: " + exception);
        }
    }

    private static String string(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString().trim() : "";
    }

    /** In-game /eskin <nick>, saved across launches. */
    public static boolean select(String nick) {
        if (!NICK.matcher(nick).matches()) return false;
        selectedNick = nick;
        CACHE.remove(accountNick.toLowerCase(Locale.ROOT));
        try {
            JsonObject o = Files.exists(CONFIG) ? JsonParser.parseString(Files.readString(CONFIG)).getAsJsonObject() : new JsonObject();
            o.addProperty("selectedNick", selectedNick);
            Files.createDirectories(CONFIG.getParent());
            Files.writeString(CONFIG, o.toString());
        } catch (Exception ex) { System.err.println("[LT6 Skins] Save failed: " + ex); }
        return true;
    }

    public static void reset() {
        selectedNick = "";
        CACHE.clear();
        try {
            if (Files.exists(CONFIG)) {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG)).getAsJsonObject();
                o.remove("selectedNick");
                Files.writeString(CONFIG, o.toString());
            }
        } catch (Exception ex) { System.err.println("[LT6 Skins] Reset failed: " + ex); }
    }

    public static String current() { return selectedNick.isBlank() ? "по умолчанию" : selectedNick; }

    public static PlayerSkin override(String name, PlayerSkin original) {
        if (!active || original == null || name == null) return original;
        String requested = name;
        // Only override own skin when /eskin was set; never replace other players' skins.
        if (!selectedNick.isBlank() && isMe(name)) requested = selectedNick;
        if (!NICK.matcher(requested).matches()) return original;
        String key = requested.toLowerCase(Locale.ROOT);
        Entry entry = CACHE.computeIfAbsent(key, ignored -> new Entry());
        if (System.currentTimeMillis() - entry.checked > (entry.found ? OK_TTL : MISS_TTL) && BUSY.add(key)) {
            entry.checked = System.currentTimeMillis();
            String lookupName = requested;
            IO.execute(() -> {
                try { fetch(lookupName, entry); }
                catch (Exception ex) {
                    System.err.println("[LT6 Skins] " + lookupName + ": " + ex);
                    entry.checked = System.currentTimeMillis();
                } finally { BUSY.remove(key); }
            });
        }
        Identifier texture = entry.texture;
        if (!entry.found || texture == null) return original;
        if (entry.original == original) return entry.replacement;
        // Explicit resource texture is necessary in 1.21.11 (the normal skin is a downloaded asset).
        PlayerSkin replaced = new PlayerSkin(new ClientAsset.ResourceTexture(texture), original.cape(), original.elytra(),
                entry.slim ? PlayerModelType.SLIM : PlayerModelType.WIDE, original.secure());
        entry.original = original;
        entry.replacement = replaced;
        return replaced;
    }

    private static boolean isMe(String name) {
        Minecraft mc = Minecraft.getInstance();
        return (mc.player != null && name.equalsIgnoreCase(mc.player.getGameProfile().name()))
                || (!accountNick.isBlank() && name.equalsIgnoreCase(accountNick));
    }

    private static void fetch(String nick, Entry e) throws Exception {
        Download download = null;
        boolean myDefault = selectedNick.isBlank() && isMe(nick);
        if (myDefault && localFile != null && Files.isRegularFile(localFile))
            download = new Download(Files.readAllBytes(localFile), localSlim);
        if (download == null && !server.isBlank()) download = request(server + "/skin/" + nick + ".png", "X-Model");
        if (download == null) download = request("https://api.mcskin.me/skin/" + nick, "X-Skin-Model");
        if (download != null) {
            // Save the last successful lookup so temporary network outages don't mean Steve/Alex.
            try {
                Files.write(DISK.resolve(nick.toLowerCase(Locale.ROOT) + ".png"), download.bytes());
                Files.writeString(DISK.resolve(nick.toLowerCase(Locale.ROOT) + ".model"), download.slim() ? "slim" : "classic");
            } catch (Exception ignored) { }
        } else {
            Path cached = DISK.resolve(nick.toLowerCase(Locale.ROOT) + ".png");
            if (Files.isRegularFile(cached))
                download = new Download(Files.readAllBytes(cached),
                        Files.exists(DISK.resolve(nick.toLowerCase(Locale.ROOT) + ".model"))
                                && Files.readString(DISK.resolve(nick.toLowerCase(Locale.ROOT) + ".model")).trim().equals("slim"));
        }
        if (download == null) { e.checked = System.currentTimeMillis(); return; }
        Download resolved = download;
        Minecraft.getInstance().execute(() -> upload(e, resolved));
    }

    private static Download request(String url, String modelHeader) {
        try {
            HttpResponse<byte[]> response = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8)).header("User-Agent", "LT6/1.0")
                    .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] bytes = response.body();
            if (response.statusCode() != 200 || bytes.length < 24 || bytes.length > 65536 ||
                    bytes[0] != (byte) 137 || bytes[1] != 80 || bytes[2] != 78 || bytes[3] != 71) return null;
            return new Download(bytes, "slim".equalsIgnoreCase(response.headers().firstValue(modelHeader).orElse("classic")));
        } catch (Exception exception) { return null; }
    }

    private static void upload(Entry entry, Download skin) {
        try {
            NativeImage img = NativeImage.read(new ByteArrayInputStream(skin.bytes()));
            if (img.getWidth() != 64 || img.getHeight() != 64) { img.close(); return; }
            DynamicTexture texture = new DynamicTexture(() -> "LT6 player skin", img);
            texture.upload();
            Identifier id = Identifier.fromNamespaceAndPath("enchanted_client", "skins/skin_" + System.nanoTime());
            Minecraft.getInstance().getTextureManager().register(id, texture);
            entry.texture = id;
            entry.slim = skin.slim();
            entry.original = null;
            entry.found = true;
            entry.checked = System.currentTimeMillis();
        } catch (Exception ex) {
            System.err.println("[LT6 Skins] Texture upload: " + ex);
            entry.checked = System.currentTimeMillis();
        }
    }
}
