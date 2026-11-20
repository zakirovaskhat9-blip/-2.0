# LT6 quality pass — Fabric 1.21.11

This archive is source code for GitHub Actions, not a verified playable JAR.

## Verified fixes

- All 231 grading profiles now use `minecraft:core/screenquad` (Minecraft 1.21.11), rather than the nonexistent `screentriangle`.
- The grading fragment shader uses GLSL 330 inputs/outputs compatible with Minecraft examples and no longer *requires* the optional `GL_ARB_separate_shader_objects` extension.
- Contrast strength now ranges from 0.85 to 1.5; negative setting desaturates and positive setting increases saturation. Depth-protected sky handling remains. Custom Sky still disables the grading shader for compatibility, until a game-tested depth exclusion is available.
- Hotkeys assigned to visual modules read the actual Minecraft window, rather than the thread's GL current context. The LT6 menu still defaults to RIGHT SHIFT.
- `HitColorMixin` now requires the verified Mojang-mapped `getModelTint` hook. If the hook changes, the game will report a mixin error instead of silently ignoring Hit Color.
- Jump circle emission is capped at 3 waves and 18 particle positions per wave per tick to control particle count.
- The CI validator now checks the post-effect shader names, mappings, sky packs, and keybind source.

## What remains unverified

The environment cannot access Gradle repositories or the .NET SDK. **Full compilation, shader rendering, hit-color visuals, frame-time performance and in-game skybox behavior have not been tested**. Only source-level and configuration checks were run.

After publishing to GitHub run Build LT6MS Launcher. Download both artifacts and test in Minecraft 1.21.11:

1. LT6 listed in Mod Menu; Right Shift opens GUI.
2. Contrast and temperature apply with Custom Sky disabled.
3. Sky remains unmodified when a sky resource pack is enabled.
4. Hit Color during damage, Jump Circles after jumping, Target ESP orbit and distance scale.
5. Sky resource packs use the installed Skyboxify renderer; test each of 6 supplied packs.

If the game fails, inspect `latest.log` and the first `Caused by:` exception.

## Reference

Vanilla 1.21.11 `post_effect/creeper.json` and `entity_outline.json` use `minecraft:core/screenquad`. The project uses a new implementation with no third-party copyrighted code or visual assets taken from Lunar Client, Fly Visuals or Pulse.
