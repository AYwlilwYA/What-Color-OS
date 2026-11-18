"""统计色键帧 G 通道全图分布，区分硬边+窄AA / 宽软渐变 / 噪声三种形态。

mid% 大不等于羽化宽：峰值 254 也落进 1..249 桶，须配 core>=250 一起看。
用法：python -I scripts/mask_channel_dist.py [素材根目录]
"""
import zlib, os, sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    'app', 'src', 'main', 'assets', 'aod', 'photo')
SPEC = {
    'leave_light': (720, 61),
    'moon': (720, 32),
    'door': (720, 41),
    'mountain': (1080, 43),
    'galaxy': (1080, 56),
}


def g(path, side):
    raw = zlib.decompress(open(path, 'rb').read())
    assert len(raw) == side * side * 3
    return raw[1::3]


def stats(a):
    n = len(a)
    z = a.count(0)
    f = a.count(255)
    mid = n - z - f
    # 「核心」= G>=250 的像素占比（几乎全透壁纸）
    core = sum(1 for v in a if v >= 250)
    # 「羽化候选」= 1..249
    return z, mid, f, core


print('=== 每帧 G 分布：判定「硬边+窄AA」还是「宽软过渡」===')
print('mid%% = 1..249 占比；core%% = >=250 占比')
print('若 mid%% 很小(<2%%) ⇒ 硬边+窄AA；若 mid%% 大且随帧增长 ⇒ 宽软过渡是设计的一部分')
print()
for folder, (side, frames) in SPEC.items():
    print('--- %s (side=%d, frames=%d) ---' % (folder, side, frames))
    for name in ['start', '000', '%03d' % (frames // 4), '%03d' % (frames // 2),
                 '%03d' % (frames * 3 // 4), '%03d' % (frames - 1), 'end']:
        s = side if name != 'end' else 720
        a = g(os.path.join(ROOT, folder, name + '.rgbz'), s)
        z, mid, f, core = stats(a)
        n = len(a)
        print('  %-5s  0=%5.1f%%  mid=%5.1f%%  255=%5.1f%%  core>=250=%5.1f%%'
              % (name, 100.0 * z / n, 100.0 * mid / n, 100.0 * f / n, 100.0 * core / n))
    print()