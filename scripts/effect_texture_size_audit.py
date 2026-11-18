import os, struct, zlib, io, json

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'effect', 'lock')
CFG = os.path.join(ROOT, 'reference/extracted/wallpapers-16/uiphy/assets/uiphy/patternglass/config.json')

FILE2NAME = {
    'tight_stripes.webp': 'TightStripes',
    'alt_stripes.webp': 'AltStripes',
    'sparse_stripes.png': 'SparseStripes',
    'rhombus_grid.webp': 'RhombusGrid',
    'water_ripples.webp': 'WaterRipples',
    'spiral_grain.webp': 'SpiralGrain',
}


def size_of(path):
    b = open(path, 'rb').read(64)
    if b[:8] == b'\x89PNG\r\n\x1a\n':
        return struct.unpack('>II', b[16:24])
    if b[:4] == b'RIFF' and b[8:12] == b'WEBP':
        if b[12:16] == b'VP8X':
            return (1 + int.from_bytes(b[24:27], 'little'),
                    1 + int.from_bytes(b[27:30], 'little'))
        if b[12:16] == b'VP8L':
            n = int.from_bytes(b[21:25], 'little')
            return ((n & 0x3FFF) + 1, ((n >> 14) & 0x3FFF) + 1)
        return (struct.unpack('<H', b[26:28])[0] & 0x3FFF,
                struct.unpack('<H', b[28:30])[0] & 0x3FFF)
    raise ValueError('unknown image format: ' + path)


cfg = json.load(io.open(CFG, encoding='utf-8'))
patterns = {x['name']: x for x in cfg['patterns']}

print('%-22s %-14s %-12s %-22s %s' % ('文件', '实测尺寸', '系统region', '判定', '备注'))
bad = 0
for f, name in FILE2NAME.items():
    p = os.path.join(ASSETS, f)
    if not os.path.exists(p):
        print('%-22s MISSING' % f)
        bad += 1
        continue
    w, h = size_of(p)
    reg = patterns[name]['props'][0]['region']
    note = ''
    ok = True
    if any(reg):
        if reg[2] > w or reg[3] > h:
            ok = False
            note = 'region 超出贴图'
        elif reg[2] == w and reg[3] == h:
            note = 'region 恰好整图'
        else:
            note = 'region 是子区'
    else:
        note = '整图'
    if h == 1 or w == 1:
        note += '（一维条纹图）'
    if not ok:
        bad += 1
    print('%-22s %-14s %-12s %-22s %s'
          % (f, '%dx%d' % (w, h), str(reg), 'OK' if ok else '**BAD**', note))

print()
print('bad=%d' % bad)
raise SystemExit(1 if bad else 0)