"""Fast CI integrity checks. This does not replace Gradle or Minecraft runtime tests."""
from pathlib import Path
import json
import zipfile

root = Path(__file__).resolve().parents[1]
mod = root / 'client-mod/src/main'
res = mod / 'resources'
source = mod / 'java/dev/lt6/client'
skies = root / 'Enchanted/sky_packs'

manifest = json.loads((res/'fabric.mod.json').read_text(encoding='utf8'))
assert manifest['id'] == 'lt6', manifest['id']
assert manifest['entrypoints']['client'] == ['dev.lt6.client.LT6Client']
assert manifest['depends']['minecraft'] == '~1.21.11'
mixins = json.loads((res/'lt6.mixins.json').read_text(encoding='utf8'))
for name in mixins['client']:
    assert (source/'mixin'/f'{name}.java').is_file(), name
assert not (mod/'java/dev/enchanted').exists()
assert not (source/'SkyPano.java').exists()
assert not (source/'mixin/SkyBodiesMixin.java').exists()
assert (source/'SkyPacks.java').is_file()

shader = res/'assets/lt6/shaders/post/grading.fsh'
assert shader.is_file()
code = shader.read_text(encoding='utf8')
assert '#version 330' in code and 'GL_ARB_separate_shader_objects : require' not in code
for keyword in ['DepthSampler', 'ColorTuning', 'texture(InSampler', 'fragColor']:
    assert keyword in code, keyword
profiles = sorted((res/'assets/lt6/post_effect/grading').glob('*.json'))
assert len(profiles) == 21*11, len(profiles)
for path in profiles:
    data = json.loads(path.read_text(encoding='utf8'))
    assert data['targets'] == {'swap': {}}
    assert len(data['passes']) == 2
    assert all(p['vertex_shader'] == 'minecraft:core/screenquad' for p in data['passes']), path
    assert data['passes'][0]['fragment_shader'] == 'lt6:post/grading'
    inputs = data['passes'][0]['inputs']
    assert any(x.get('use_depth_buffer') for x in inputs), path
    assert data['passes'][1]['output'] == 'minecraft:main'
    tune = data['passes'][0]['uniforms']['GradingConfig'][0]['value']
    assert len(tune) == 4 and 0 <= tune[0] <= 3.0 and 0.85 <= tune[1] <= 1.5 and -1 <= tune[2] <= 1

names = {'LT6_Clouds.zip','LT6_Green.zip','LT6_Teal.zip','LT6_Akame.zip','LT6_Violet.zip','LT6_BlueMoon.zip'}
actual = {p.name for p in skies.glob('LT6_*.zip')}
assert actual == names, (actual,names)
for name in names:
    with zipfile.ZipFile(skies/name) as z:
        assert z.testzip() is None, name
        for f in ['pack.mcmeta','assets/minecraft/optifine/sky/world0/sky1.properties','assets/minecraft/optifine/sky/world0/sky1.png']:
            assert f in z.namelist(), (name,f)
        meta = json.loads(z.read('pack.mcmeta'))
        assert meta['pack']['min_format'] == 75 and meta['pack']['max_format'] == 75

cfg = (source/'Cfg.java').read_text(encoding='utf8')
assert 'SkyPacks.select' in cfg
assert 'mc.getWindow().handle()' in (source/'Binds.java').read_text(encoding='utf8')
assert 'require = 1' in (source/'mixin/HitColorMixin.java').read_text(encoding='utf8')
assert '"Контраст", -100, 100' in cfg
assert 'ColorFX.tick(mc)' in (source/'LT6Client.java').read_text(encoding='utf8')
assert 'GLFW_KEY_RIGHT_SHIFT' in (source/'LT6Keys.java').read_text(encoding='utf8')
csproj = (root/'Enchanted/Enchanted.csproj').read_text(encoding='utf8')
assert 'client_mod/lt6.jar' in csproj
assert 'sky_packs/*.zip' in csproj
print(f'OK: LT6 source, Fabric config, {len(profiles)} shader profiles and {len(names)} sky packs checked')
