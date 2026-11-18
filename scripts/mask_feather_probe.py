"""量色键帧 G 通道的边缘羽化宽度：从 G=0 单调爬到首个 G>=250 的像素跨度。

起点必须是 0，排除形状内部渐变；终点 >=250，即几乎全透壁纸。
用法：python -I scripts/mask_feather_probe.py [素材根目录]
"""
import zlib, os, sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    'app', 'src', 'main', 'assets', 'aod', 'photo')


def g(path, side):
    raw = zlib.decompress(open(path, 'rb').read())
    assert len(raw) == side * side * 3
    return raw[1::3]


def edge_ramp(line):
    """从「最近一个 0 像素」到「首个 >=250」的跨度，且要求该段单调不减。

    关键约束：起点必须是 0（不是任意低点），终点必须 >=250（几乎全透）。
    这排除了「形状内部的宽渐变」——内部渐变起点不是 0。
    """
    n = len(line)
    out = []
    last_zero = -1
    mono_start = -1
    for i in range(n):
        v = line[i]
        if v == 0:
            last_zero = i
            mono_start = i
            continue
        if mono_start < 0:
            continue
        if i > 0 and v < line[i - 1]:
            # 单调断了，重置起点为最近一个 0（若还在后面）
            mono_start = last_zero if last_zero >= 0 else -1
            if mono_start >= 0 and mono_start < i:
                # 检查从 last_zero 到 i 是否单调
                ok = all(line[k] >= line[k - 1] for k in range(mono_start + 1, i + 1))
                if not ok:
                    mono_start = -1
            continue
        if v >= 250 and last_zero >= 0 and mono_start == last_zero:
            out.append(i - last_zero)
            mono_start = -1
    return out


print('=== 边缘羽化：从 G=0 到首个 G>=250 的单调跨度 ===')
print('起点必须是 0（排除内部渐变），终点 >=250（几乎全透壁纸）')
print()
CASES = [
    ('leave_light', 720, '000'), ('leave_light', 720, '015'),
    ('leave_light', 720, '030'), ('leave_light', 720, '045'),
    ('moon', 720, '008'), ('moon', 720, '016'),
    ('door', 720, '000'), ('door', 720, '020'),
    ('mountain', 1080, '010'), ('galaxy', 1080, '028'),
]
for folder, side, name in CASES:
    a = g(os.path.join(ROOT, folder, name + '.rgbz'), side)
    vals = []
    for y in (side // 4, side // 2, side * 3 // 4):
        vals += edge_ramp(a[y * side:(y + 1) * side])
    for x in (side // 4, side // 2, side * 3 // 4):
        vals += edge_ramp(a[x::side])
    if not vals:
        print('%-12s %-5s side=%4d  无「0→250」边缘过渡' % (folder, name, side))
    else:
        vals.sort()
        med = vals[len(vals) // 2]
        print('%-12s %-5s side=%4d  命中%2d  min=%3d p50=%3d max=%3d  → 1080屏 p50=%.0f px  全部=%s'
              % (folder, name, side, len(vals), vals[0], med, vals[-1],
                 med * 1080.0 / side, vals[:12]))
    print()