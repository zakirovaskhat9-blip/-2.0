# Enchanted skin system (Minecraft Java 1.21.11, Fabric)

## In game
- `/eskin Dream` — use Dream's public skin on **your own player, locally**.
- `/eskin reset` — go back to your own skin (local PNG from the launcher if set, otherwise your normal public skin).
- `/eskin` — show selected nickname.
- Other players' skins are fetched automatically by in-game profile nickname, with disk cache.
- The selected nickname persists after restart, including when the launcher rewrites `config/enchanted-skins.json`.

## Download sources
1. Optional personal Enchanted skin server (`https://.../skin/<nick>.png`) if configured in launcher.
2. Public raw PNG endpoint `https://api.mcskin.me/skin/<nick>`; header `X-Skin-Model` determines Alex or Steve shape.
3. Previously downloaded PNG and model in `config/enchanted-skin-cache/` when offline.

The public API caches Mojang data; it is **not a NameMC API** and cannot restore skins never retrieved by it. It may have rate limits or become unavailable. The custom server takes priority when configured. Skin changes affect only this Minecraft client: other players on vanilla servers cannot see a local skin override.

## Build and testing
GitHub Actions `.github/workflows/build.yml` builds the Fabric mod then embeds it into the Windows launcher. The ZIP contains **sources**, not compiled EXE/JAR. Build requires Java 25, Gradle 9.8.2, Windows and .NET 8 for the launcher. This environment has neither Gradle nor dotnet, so end-to-end build/game run is unverified.

## Security / behavior
- No external skin service is granted login credentials or access tokens.
- Incoming images are size-limited (64 KiB) and decoded as 64x64 PNG.
- Only use HTTPS custom servers (or local loopback HTTP).
- Cache is local to this client and may grow over time.
