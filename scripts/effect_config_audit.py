import json, io, re

"""逐项对账六档效果参数与系统 patternglass/config.json 原文。

用法：python -I scripts/effect_config_audit.py [仓库根目录]
退出码 0=全等，1=有差异。
"""
import os, sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(
    os.path.dirname(os.path.abspath(__file__)))
CFG = os.path.join(ROOT, 'reference/extracted/wallpapers-16/uiphy/assets/uiphy/patternglass/config.json')
SRC = os.path.join(ROOT, 'app/src/main/kotlin/com/coloros16/liquidglass/engine/effect/EffectTextureCatalog.kt')

d = json.load(io.open(CFG, encoding='utf-8'))
by = lambda l: {x['name']: x for x in l}
lights, glasses, patterns, blurs = by(d['lights']), by(d['glasses']), by(d['patterns']), by(d['blurs'])

src = io.open(SRC, encoding='utf-8').read()
i = src.find('private val specs')
body = src[i:]
blocks = re.findall(r'EffectTextureSpec\(\s*\n(.*?)\n\s*\),', body, re.S)

FILE2NAME = {
    'tight_stripes.webp': 'TightStripes',
    'alt_stripes.webp': 'AltStripes',
    'sparse_stripes.png': 'SparseStripes',
    'rhombus_grid.webp': 'RhombusGrid',
    'water_ripples.webp': 'WaterRipples',
    'spiral_grain.webp': 'SpiralGrain',
}
WRAP = {'CROP_OR_STRETCH': 'CropOrStretch', 'REPEAT': 'Repeat', 'STRETCH': 'Stretch'}

parsed = {}
for b in blocks:
    m = re.search(r'DIR \+ "([^"]+)"', b)
    if not m:
        continue
    fname = m.group(1)
    toks = re.findall(r'"([^"]*)"|TextureWrap\.(\w+)|intArrayOf\(([^)]*)\)|(-?\d+(?:\.\d+)?)f?', b)
    seq = []
    for s, w, arr, n in toks:
        if s:
            seq.append(s)
        elif w:
            seq.append(WRAP[w])
        elif arr:
            seq.append([int(x.strip()) for x in arr.split(',')])
        elif n:
            seq.append(float(n))
    # seq[0]=文件名, 1=layoutScale, 2=region, 3=wrapX, 4=wrapY, 5=glassColor,
    # 6=thickness 7=refractivity 8=specular 9=blurThickness 10=blurRefractivity 11=blurSpecular
    # 12=lightAngle 13=lightStrength 14=lightColor 15=blurStep 16=blurRadius 17=blurredStep 18=blurredRadius
    parsed[fname] = seq

fails = []
print('%-14s %-22s %-14s %-14s %s' % ('档', '项', '系统真值', '我方', '判定'))
for fname, name in FILE2NAME.items():
    s = parsed.get(fname)
    if s is None:
        fails.append((name, 'PARSE', None, None))
        continue
    p0 = patterns[name]['props'][0]
    g0 = glasses[name]['props'][0]
    gb = glasses[name + '.Blur']['props'][0]
    l0 = lights[name]['props'][0]
    b0 = blurs[name]['props'][0]
    bb = blurs[name + '.Blur']['props'][0]
    checks = [
        ('layoutScale', p0['layoutScale'], s[1]),
        ('region', p0['region'], s[2]),
        ('wrapX', p0.get('wrapXMode', 'CropOrStretch'), s[3]),
        ('wrapY', p0.get('wrapYMode', 'CropOrStretch'), s[4]),
        ('glassColor', g0['color'], s[5]),
        ('thickness', g0['thickness'], s[6]),
        ('refractivity', g0['refractivity'], s[7]),
        ('specular', g0['specular'], s[8]),
        ('blurThickness', gb['thickness'], s[9]),
        ('blurRefractivity', gb['refractivity'], s[10]),
        ('blurSpecular', gb['specular'], s[11]),
        ('lightAngle', l0['horizontalAngle'], s[12]),
        ('lightStrength', l0['strength'], s[13]),
        ('lightColor', l0['color'], s[14]),
        ('blurStep', b0['step'], s[15]),
        ('blurRadius', b0['radius'], s[16]),
        ('blurredStep', bb['step'], s[17]),
        ('blurredRadius', bb['radius'], s[18]),
    ]
    for key, want, got in checks:
        ok = (want == got) if not isinstance(want, float) else abs(want - got) < 1e-6
        if isinstance(want, list):
            ok = want == got
        mark = 'OK' if ok else '**DIFF**'
        if not ok:
            fails.append((name, key, want, got))
        print('%-14s %-22s %-14s %-14s %s' % (name, key, want, got, mark))
    print()

print('=== 差异汇总 (%d) ===' % len(fails))
for n, k, w, g in fails:
    print('  %s.%s  系统=%s  我方=%s' % (n, k, w, g))

sys.exit(1 if fails else 0)
