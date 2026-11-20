"""截屏像素量取：纯 stdlib 解 PNG，量时钟字框 / 虚线选框 / 右下手柄圆点。

用途=E2 验「手柄变形左上角钉死」「拉伸后宽高比真的变了」这类必须落到像素的判据；
不依赖 PIL（本机系统 python 无 PIL），也不改被测设备任何状态。

用法：
  python -I scripts/measure_clock_glyph.py <png> [png ...]        量全部三项
  python -I scripts/measure_clock_glyph.py --box y0,y1 <png>      限定纵向搜索窗
  python -I scripts/measure_clock_glyph.py --handle x0,x1,y0,y1 <png>  限定手柄搜索窗

判据提示：比较两帧务必用同一阈值同一窗口，跨图换阈值会得出假结论。
"""
import struct
import sys
import zlib


def readpng(path):
    """解 8bit RGB/RGBA 非隔行 PNG，返回 (w, h, bpp, pixels)。"""
    b = open(path, 'rb').read()
    if b[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError('不是 PNG: ' + path)
    i = 8
    idat = b''
    w = h = bpp = None
    while i < len(b):
        ln = struct.unpack('>I', b[i:i + 4])[0]
        typ = b[i + 4:i + 8]
        data = b[i + 8:i + 8 + ln]
        if typ == b'IHDR':
            w, h, bd, ct, comp, filt, inter = struct.unpack('>IIBBBBB', data[:13])
            if bd != 8 or ct not in (2, 6) or inter != 0:
                raise ValueError('只支持 8bit RGB/RGBA 非隔行，实为 bd=%d ct=%d inter=%d' % (bd, ct, inter))
            bpp = 4 if ct == 6 else 3
        elif typ == b'IDAT':
            idat += data
        i += 12 + ln
    raw = zlib.decompress(idat)
    stride = w * bpp
    out = bytearray()
    prev = bytearray(stride)
    pos = 0
    for _ in range(h):
        f = raw[pos]
        pos += 1
        line = bytearray(raw[pos:pos + stride])
        pos += stride
        if f:
            for x in range(stride):
                a = line[x - bpp] if x >= bpp else 0
                bb = prev[x]
                c = prev[x - bpp] if x >= bpp else 0
                if f == 1:
                    line[x] = (line[x] + a) & 255
                elif f == 2:
                    line[x] = (line[x] + bb) & 255
                elif f == 3:
                    line[x] = (line[x] + (a + bb) // 2) & 255
                elif f == 4:
                    pp = a + bb - c
                    pa, pb, pc = abs(pp - a), abs(pp - bb), abs(pp - c)
                    pr = a if (pa <= pb and pa <= pc) else (bb if pb <= pc else c)
                    line[x] = (line[x] + pr) & 255
                else:
                    raise ValueError('未知行滤波 %d' % f)
        out += line
        prev = line
    return w, h, bpp, bytes(out)


def glyph_box(w, h, bpp, p, y0, y1, thresh=240):
    """近白像素外接框 = 时钟字框；返回 (left, right, top, bottom) 或 None。"""
    xs = []
    ys = []
    for y in range(max(0, y0), min(h, y1)):
        base = y * w
        for x in range(w):
            o = (base + x) * bpp
            if p[o] > thresh and p[o + 1] > thresh and p[o + 2] > thresh:
                xs.append(x)
                ys.append(y)
    if not xs:
        return None
    return min(xs), max(xs), min(ys), max(ys)


def dash_top(w, h, bpp, p, y0, y1, min_run=30):
    """虚线选框上边缘：第一行满足配色且横向命中够多的 y；返回 (y, xmin, xmax)。"""
    for y in range(max(0, y0), min(h, y1)):
        base = y * w
        xs = [x for x in range(w)
              if p[(base + x) * bpp + 2] > 240
              and p[(base + x) * bpp] > 140
              and p[(base + x) * bpp + 1] > 170]
        if len(xs) >= min_run:
            return y, min(xs), max(xs)
    return None


def handle_dot(w, h, bpp, p, x0, x1, y0, y1, min_run=15):
    """右下角实心手柄圆点：逐行取最长同色游程，返回 (cx, cy, top, bottom)。"""
    rows = []
    for y in range(max(0, y0), min(h, y1)):
        run = best = start = best_start = 0
        for x in range(max(0, x0), min(w, x1)):
            o = (y * w + x) * bpp
            if p[o] > 140 and p[o + 1] > 170 and p[o + 2] > 240:
                if run == 0:
                    start = x
                run += 1
                if run > best:
                    best, best_start = run, start
            else:
                run = 0
        if best >= min_run:
            rows.append((y, best, best_start))
    if not rows:
        return None
    ys = [r[0] for r in rows]
    widest = max(rows, key=lambda r: r[1])
    r = (max(ys) - min(ys)) // 2
    return widest[2] + r, (min(ys) + max(ys)) // 2, min(ys), max(ys)


def main(argv):
    box = None
    handle = None
    paths = []
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == '--box':
            i += 1
            box = tuple(int(v) for v in argv[i].split(','))
        elif a == '--handle':
            i += 1
            handle = tuple(int(v) for v in argv[i].split(','))
        else:
            paths.append(a)
        i += 1
    if not paths:
        print(__doc__)
        return 1
    for path in paths:
        w, h, bpp, p = readpng(path)
        y0, y1 = box if box else (0, h)
        g = glyph_box(w, h, bpp, p, y0, y1)
        d = dash_top(w, h, bpp, p, y0, y1)
        size = (g[1] - g[0], g[3] - g[2]) if g else None
        ratio = (size[0] / size[1]) if size and size[1] else None
        line = '%s %dx%d glyph=%s size=%s ratio=%s dash=%s' % (
            path, w, h, g, size, ('%.4f' % ratio) if ratio else None, d)
        if handle:
            line += ' handle=%s' % (handle_dot(w, h, bpp, p, *handle),)
        print(line)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
