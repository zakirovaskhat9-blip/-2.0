"""LT6 release gate: check release inputs before Gradle and Win64 publishing.

This is intentionally not a replacement for Gradle compilation or in-game smoke tests.
Designed to catch common regressions that previously caused green workflows with
missing JARs or incorrectly bundled resource packs.
"""
from pathlib import Path
from zipfile import ZipFile
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'client-mod/src/main/java/dev/lt6/client'
RES = ROOT / 'client-mod/src/main/resources'
SHADERS = RES / 'assets/lt6/post_effect/grading'
PACKS = ROOT / 'Enchanted/sky_packs'
WORKFLOW = (ROOT / '.github/workflows/build.yml').read_text(encoding='utf-8')
CS_PROJ = (ROOT / 'Enchanted/Enchanted.csproj').read_text(encoding='utf-8')

def check(condition, message):
    if not condition:
        raise AssertionError(message)

manifest = json.loads((RES / 'fabric.mod.json').read_text(encoding='utf-8'))
check(manifest['id'] == 'lt6', 'Fabric module ID must be lt6')
check(manifest['entrypoints'].get('client') == ['dev.lt6.client.LT6Client'], 'Missing LT6 client initializer')
check(manifest['environment'] == 'client', 'This LT6 build must be client-only')
check(manifest['depends'].get('minecraft') == '~1.21.11', 'Wrong Minecraft version')
check((JAVA / 'LT6Client.java').is_file(), 'LT6 client initializer source not found')
check('GLFW_KEY_Y' in (JAVA/'LT6Keys.java').read_text(encoding='utf-8'), 'Y key default missing')
check('ClientTickEvents.END_CLIENT_TICK' in (JAVA/'LT6Client.java').read_text(encoding='utf-8'), 'Client tick hook missing')

mix = json.loads((RES / 'lt6.mixins.json').read_text(encoding='utf-8'))
check(mix['package'] == 'dev.lt6.client.mixin', 'Mixin package mismatch')
for name in mix['client']:
    check((JAVA / 'mixin' / (name + '.java')).exists(), f'Mixin source missing: {name}')

check(not (ROOT/'client-mod/src/main/java/dev/enchanted').exists(), 'Obsolete visual Java package remains')
check(not (RES/'assets/enchanted_client').exists(), 'Obsolete visual assets remain')
check('enchanted-client-*.jar' not in WORKFLOW, 'Workflow still uses old visual module name')
check('lt6-*.jar' in WORKFLOW, 'Workflow does not capture new LT6 JAR')
check('if-no-files-found: error' in WORKFLOW, 'Release artifacts could go missing silently')
check('client_mod/lt6.jar' in CS_PROJ, 'Launcher does not embed LT6 JAR')
check('sky_packs/*.zip' in CS_PROJ, 'Launcher does not embed sky packs')
check('upload-artifact@v4' in WORKFLOW, 'Workflow does not upload release artifacts')
check('LT6-Mod-JAR' in WORKFLOW and 'LT6MS-Launcher' in WORKFLOW, 'Missing artifacts')

post = (RES/'assets/lt6/shaders/post/grading.fsh').read_text(encoding='utf-8')
for required in ('#version 330', 'InSampler', 'DepthSampler', 'GradingConfig', 'fragColor'):
    check(required in post, f'Missing shader input: {required}')
check('GL_ARB_separate_shader_objects : require' not in post, 'Unsupported mandatory GLSL extension')
profiles = sorted(SHADERS.glob('s*_w*.json'))
check(len(profiles) == 21*11, 'Expected 231 gradient profiles')
live = SHADERS / 'live.json'
check(live.is_file(), 'Live grading profile missing')
live_config = json.loads(live.read_text(encoding='utf-8'))
check(live_config['passes'][0]['fragment_shader'] == 'lt6:post/grading_live', 'Live grading shader missing')
check(len(live_config['passes'][0]['uniforms']['GradingConfig']) == 2, 'Six live grading controls need 2 vec4s')
for item in profiles:
    data = json.loads(item.read_text(encoding='utf-8'))
    check(data['targets'] == {'swap': {}}, f'Bad targets: {item.name}')
    check(len(data['passes']) == 2, f'Bad shader pass count: {item.name}')
    first, last = data['passes']
    check(first['vertex_shader'] == 'minecraft:core/screenquad', f'Bad vertex shader: {item.name}')
    check(first['fragment_shader'] == 'lt6:post/grading', f'Bad fragment shader: {item.name}')
    check(last['vertex_shader'] == 'minecraft:core/screenquad', f'Bad blit vertex shader: {item.name}')
    check(last['output'] == 'minecraft:main', f'Bad final output: {item.name}')
    check(any(x.get('use_depth_buffer') for x in first['inputs']), f'No depth protection: {item.name}')
    vals = first['uniforms']['GradingConfig'][0]['value']
    check(len(vals) == 4 and all(isinstance(v, (int, float)) for v in vals), f'Bad GLSL uniform: {item.name}')

packs = sorted(PACKS.glob('LT6_*.zip'))
check(len(packs) == 6, 'Expected six new Custom Sky packs')
for pack in packs:
    with ZipFile(pack) as z:
        check(z.testzip() is None, f'Corrupt sky ZIP: {pack.name}')
        for needed in ('pack.mcmeta', 'assets/minecraft/optifine/sky/world0/sky1.properties',
                       'assets/minecraft/optifine/sky/world0/sky1.png'):
            check(needed in z.namelist(), f'{pack.name} missing {needed}')
        meta = json.loads(z.read('pack.mcmeta'))
        check(meta['pack']['min_format'] == 75 and meta['pack']['max_format'] == 75, f'Bad pack version: {pack.name}')

# Avoid regression of the invisible resource-reload screen deadlock.
loading = (JAVA/'mixin/LT6LoadingOverlayMixin.java').read_text(encoding='utf-8')
check('this.fadeInStart = now' in loading, 'Silent reload must advance fadeInStart or mouse remains blocked')
check('this.fadeOutStart' in loading and 'setOverlay(null)' in loading,
      'Silent reload must eventually release the overlay')
# Editing must remain possible after the first model drag.
screen = (JAVA/'LT6Screen.java').read_text(encoding='utf-8')
check('section == 1' in screen and 'section == 2' in screen
      and '"Visuals", "Player", "GUI"' in screen,
      'LT6 must expose Visuals, Player and GUI navigation')
check('Cfg.ESP_TINT_SECONDARY' in screen and 'Cfg.ESP_PAINT' in screen,
      'Target ESP must offer secondary colour and colour patterns')
check('Cfg.JUMP_PATTERN' in screen and 'Cfg.JUMP_TINT' in screen,
      'Jump FX needs effect and colour settings')
check('Cfg.UI_PART_STYLE' in screen and 'Cfg.UI_BLUR' in screen,
      'GUI particles and optional background blur must be configurable')
check((JAVA/'mixin/LT6FastXPMixin.java').is_file()
      and 'startUseItem' in (JAVA/'mixin/LT6FastXPMixin.java').read_text(encoding='utf-8')
      and '@ModifyConstant' in (JAVA/'mixin/LT6FastXPMixin.java').read_text(encoding='utf-8'),
      'Fast XP must reduce vanilla item-use cooldown without extra tick packets')
check((JAVA/'mixin/LT6SkySunMixin.java').is_file()
      and 'renderSun' in (JAVA/'mixin/LT6SkySunMixin.java').read_text(encoding='utf-8'),
      'Hide only the vanilla sun when Custom Sky is active')
chat = (JAVA/'ChatHudEditor.java').read_text(encoding='utf-8')
check('paintHandle' not in chat and 'draw' not in chat.split('public static void render(')[-1],
      'No crosses or grip overlays in the chat editor')
check('hasLast[t.ordinal()]' in chat and 'lastX[dragging.ordinal()]' in chat,
      'Drag and wheel controls must follow the last moved model position')
check('Scale.v' not in chat or 'scaleSetting(target)' in chat,
      'Mouse wheel resizing must remain enabled for hands and items')
cape = (ROOT/'Enchanted/Core/CapeStudio.cs').read_text(encoding='utf-8')
check('CanvasHeight = 1024' in cape and 'Resolution = 2048' in cape,
      'Prepared cape must use high-resolution Minecraft 64x32 UV aspect')
check('SourceFile(destination)' in cape and 'new CroppedBitmap' in cape,
      'Preserve the original photo and preview the actual cape UV face')
cape_runtime = (JAVA/'CapeTextures.java').read_text(encoding='utf-8')
check('width != height * 2' in cape_runtime,
      'Client cape texture dimensions must match 64x32 UV coordinates')

java_text = '\n'.join(p.read_text(encoding='utf-8') for p in JAVA.rglob('*.java'))
check('glBegin(' not in java_text and 'glEnd(' not in java_text, 'Legacy GL calls cannot run in Core Profile')
check('SkyPano' not in java_text, 'Legacy sky renderer reference remains')
check('continuousAngle' in (JAVA/'Hud.java').read_text(encoding='utf-8'), 'Uniform ESP rotation code missing')
check('System.nanoTime()' in (JAVA/'Hud.java').read_text(encoding='utf-8'), 'ESP rotation should use continuous time')

print(f'PASS: Fabric, mixins, bindings, launcher embedding, CI release artifacts, {len(profiles)} shader profiles, {len(packs)} Custom Sky packs and ESP source.')
print('NOTE: Gradle build, Windows WPF publish and Minecraft GPU runtime tests are separate required checks.')
