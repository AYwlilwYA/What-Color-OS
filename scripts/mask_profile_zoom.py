"""打印色键帧 G 通道指定窗口的逐点数值，用于核对边缘形状。

段长统计不能单独作结论，必须配逐点剖面区分羽化与内部渐变。
用法：python -I scripts/mask_profile_zoom.py [素材根目录]
"""
import zlib, os, sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    'app', 'src', 'main', 'assets', 'aod', 'photo')


def g(path, side):
    raw = zlib.decompress(open(path, 'rb').read())
    assert len(raw) == side * side * 3
    return raw[1::3]


def show(folder, side, name, y, x0, x1):
    a = g(os.path.join(ROOT, folder, name + '.rgbz'), side)
    row = a[y * side:(y + 1) * side]
    seg = row[x0:x1]
    print('%s/%s y=%d x[%d..%d]:' % (folder, name, y, x0, x1))
    print('  ' + ' '.join(str(v) for v in seg))
    print()


# moon 016 中线：剖面显示 0×148 后 2,33,223,251,254×416 ⇒ 边缘只有 4px
show('moon', 720, '016', 360, 140, 165)
# leave_light 030 中线：0×198 后 194,255×322 ⇒ 边缘 1px
show('leave_light', 720, '030', 360, 194, 206)
# mountain 010 中线：从 0×73 开始缓爬 1,2,3,...  ⇒ 看它爬多久到 250
show('mountain', 1080, '010', 540, 70, 130)
show('mountain', 1080, '010', 540, 300, 360)
show('mountain', 1080, '010', 540, 500, 560)
# galaxy 028：噪声，看一段
show('galaxy', 1080, '028', 540, 175, 235)