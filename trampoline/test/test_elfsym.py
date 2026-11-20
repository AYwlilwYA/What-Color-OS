#!/usr/bin/env python3
"""符号获取件对账：elfsym.c 解析真 sf_bin，四个 generate 地址必须与已知真值一致。

宿主侧 xz 解码由本脚本预解压 .gnu_debugdata 落成临时 ELF 提供（C 侧走 .symtab 直读路径），
另单独校验 C 侧 xz 分支：把预解压件重新压缩成 xz 塞回一份副本，令 C 侧必须走解压路径。
"""
import lzma
import os
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

T = Path(__file__).resolve().parent.parent
OUT = T / "test" / "out"

TRUTH = {
    "sf_bin_c15": {
        "_ZNK7android12renderengine4skia16KawaseBlurFilter8generateEPNS1_14SkiaGpuContextEj5sk_spI7SkImageERK6SkRect": 0x418A70,
        "_ZNK7android12renderengine4skia18GaussianBlurFilter8generateEPNS1_14SkiaGpuContextEj5sk_spI7SkImageERK6SkRect": 0x640B24,
        "_ZN7android12renderengine4skia13IBlurUtilsExt8generateEP18GrRecordingContextj5sk_spI7SkImageERK6SkRect": 0x8D2194,
    },
    "sf_bin": {
        "_ZNK7android12renderengine4skia16KawaseBlurFilter8generateEPNS1_14SkiaGpuContextEj5sk_spI7SkImageERK6SkRect": 0x4DE8B0,
        "_ZNK7android12renderengine4skia18GaussianBlurFilter8generateEPNS1_14SkiaGpuContextEj5sk_spI7SkImageERK6SkRect": 0x7150E0,
        "_ZNK7android12renderengine4skia20KawaseBlurDualFilter8generateEPNS1_14SkiaGpuContextEj5sk_spI7SkImageERK6SkRect": 0x715AD8,
        "_ZN7android12renderengine4skia13IBlurUtilsExt8generateEP18GrRecordingContextj5sk_spI7SkImageERK6SkRect": 0x9C9678,
    },
}


def sections(b):
    shoff = struct.unpack_from("<Q", b, 0x28)[0]
    shent = struct.unpack_from("<H", b, 0x3A)[0]
    shnum = struct.unpack_from("<H", b, 0x3C)[0]
    shstr = struct.unpack_from("<H", b, 0x3E)[0]
    so = struct.unpack_from("<Q", b, shoff + shstr * shent + 24)[0]
    out = []
    for i in range(shnum):
        sh = shoff + i * shent
        nmo = struct.unpack_from("<I", b, sh)[0]
        end = b.find(b"\0", so + nmo)
        out.append((b[so + nmo:end].decode(), sh))
    return out


def debugdata(b):
    for nm, sh in sections(b):
        if nm == ".gnu_debugdata":
            off = struct.unpack_from("<Q", b, sh + 24)[0]
            size = struct.unpack_from("<Q", b, sh + 32)[0]
            return lzma.decompress(b[off:off + size])
    return None


HARNESS = r"""
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "elfsym.h"

static int host_xz(const unsigned char *src, size_t sn, unsigned char *dst,
                   size_t cap, size_t *out_n) {
    (void)src; (void)sn; (void)dst; (void)cap; (void)out_n;
    return -1;
}

int main(int argc, char **argv) {
    if (argc < 3) return 2;
    const char *tbl = (argc > 3 && strcmp(argv[3], "-") != 0) ? argv[3] : NULL;
    int no_stub = getenv("LG_XZ_BUILTIN") != NULL;
    if (!no_stub) lg_elfsym_set_xz_decoder(host_xz);
    if (tbl) lg_elfsym_set_table(tbl);
    unsigned long va = 0;
    int rc = lg_elfsym_find(argv[1], argv[2], &va);
    if (rc != 0) { printf("MISS\n"); return 1; }
    printf("0x%lx %d\n", va, lg_elfsym_last_source());
    return 0;
}
"""

# 短子串（引擎实际用的形式）→ 真值；C16 Dual 有同名 lambda 干扰，选优须取最短名
SUBSTR_TRUTH = {
    "sf_bin_c15": {
        "16KawaseBlurFilter8generateE": 0x418A70,
        "18GaussianBlurFilter8generateE": 0x640B24,
        "13IBlurUtilsExt8generateE": 0x8D2194,
    },
    "sf_bin": {
        "16KawaseBlurFilter8generateE": 0x4DE8B0,
        "18GaussianBlurFilter8generateE": 0x7150E0,
        "20KawaseBlurDualFilter8generateE": 0x715AD8,
        "13IBlurUtilsExt8generateE": 0x9C9678,
    },
}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    hp = OUT / "elfsym_harness.c"
    hp.write_text(HARNESS, encoding="utf-8")
    exe = OUT / "elfsym_harness"
    V = T / "vendor" / "xz-embedded"
    cmd = ["gcc", "-O1", "-DLG_HOST_TEST",
           "-include", str(V / "lg_xz_config.h"),
           "-I", str(T), "-I", str(V), str(hp), str(T / "elfsym.c"), str(T / "xzdec.c"),
           str(V / "xz_dec_stream.c"), str(V / "xz_dec_lzma2.c"),
           str(V / "xz_crc32.c"), str(V / "xz_crc64.c"), str(V / "xz_sha256.c"),
           "-ldl", "-o", str(exe)]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        print("COMPILE-FAIL\n" + r.stderr)
        return 1
    print("COMPILE-OK")

    fails = 0
    total = 0
    with tempfile.TemporaryDirectory(dir=str(OUT)) as td:
        td = Path(td)
        for name, table in TRUTH.items():
            src = OUT / name
            if not src.exists():
                print(f"SKIP {name}（二进制不在，需先 pull）")
                continue
            b = src.read_bytes()
            mini = debugdata(b)
            if mini is None:
                print(f"FAIL {name}: 无 .gnu_debugdata")
                fails += 1
                continue
            # 路径 A：预解压 mini ELF 直读 .symtab（验符号表解析逻辑）
            plain = td / f"{name}_mini.elf"
            plain.write_bytes(mini)
            # 路径 B：原二进制 + 宿主 xz 解码桩返回 -1 → 必须 MISS（证明走到了解压分支）
            for sym, want in table.items():
                total += 1
                r = subprocess.run([str(exe), str(plain), sym], capture_output=True, text=True)
                got = r.stdout.strip()
                if r.returncode != 0 or not got.startswith("0x"):
                    print(f"FAIL {name} symtab {sym[:48]}… -> {got}")
                    fails += 1
                    continue
                va = int(got.split()[0], 16)
                srcid = int(got.split()[1])
                if va != want or srcid != 1:
                    print(f"FAIL {name} {sym[:48]}… got=0x{va:x} src={srcid} want=0x{want:x} src=1")
                    fails += 1
                else:
                    print(f"PASS {name} 0x{va:x} src=symtab {sym.split('4skia')[-1][:40]}")
            # 阴性对照：不存在的符号必须 MISS
            total += 1
            r = subprocess.run([str(exe), str(plain), "_ZNK4none8generateEv"],
                               capture_output=True, text=True)
            if r.returncode == 0:
                print(f"FAIL {name} 阴性对照：不存在符号却命中")
                fails += 1
            else:
                print(f"PASS {name} 阴性对照 MISS")
            # 解压分支可达性：原件 + 桩失败 → MISS（而非误命中 dynsym）
            total += 1
            sym0 = next(iter(table))
            r = subprocess.run([str(exe), str(src), sym0], capture_output=True, text=True)
            if r.returncode == 0:
                print(f"FAIL {name} 解压桩失败仍命中（来源={r.stdout.strip()}），三级路径判定可疑")
                fails += 1
            else:
                print(f"PASS {name} 解压桩失败 → MISS（证明该符号确实只在 debugdata 内）")

    # 短子串 + 预生成表路径（引擎实际用法）：表命中 src=4，且 lambda 干扰须被选优排除
    table = T.parent / "app" / "src" / "main" / "assets" / "lg-sf" / "symtab.txt"
    gen = T.parent / "tools" / "gen_symtab.py"
    with tempfile.TemporaryDirectory(dir=str(OUT)) as td:
        td = Path(td)
        tbl = td / "symtab.txt"
        bins = [str(OUT / n) for n in SUBSTR_TRUTH if (OUT / n).exists()]
        if bins:
            r = subprocess.run([sys.executable, str(gen), *bins, "-o", str(tbl)],
                               capture_output=True, text=True)
            if r.returncode != 0:
                print("FAIL 表生成器\n" + r.stdout + r.stderr)
                fails += 1
            else:
                print("PASS 表生成器")
            total += 1
            for name, tblmap in SUBSTR_TRUTH.items():
                src = OUT / name
                if not src.exists() or not tbl.exists():
                    continue
                mini = debugdata(src.read_bytes())
                plain = td / f"{name}_mini2.elf"
                plain.write_bytes(mini)
                for sub, want in tblmap.items():
                    # 路径 4：原二进制 + 表（无需解压）
                    total += 1
                    r = subprocess.run([str(exe), str(src), sub, str(tbl)],
                                       capture_output=True, text=True)
                    got = r.stdout.strip()
                    if r.returncode != 0 or not got.startswith("0x"):
                        print(f"FAIL {name} 表路径 {sub} -> {got}")
                        fails += 1
                    else:
                        va, srcid = int(got.split()[0], 16), int(got.split()[1])
                        if va != want or srcid != 4:
                            print(f"FAIL {name} 表 {sub} got=0x{va:x} src={srcid} want=0x{want:x} src=4")
                            fails += 1
                        else:
                            print(f"PASS {name} 0x{va:x} src=table {sub}")
                    # 路径 1：短子串直读 symtab（选优须排除 lambda）
                    total += 1
                    r = subprocess.run([str(exe), str(plain), sub], capture_output=True, text=True)
                    got = r.stdout.strip()
                    if r.returncode != 0 or not got.startswith("0x"):
                        print(f"FAIL {name} 子串 symtab {sub} -> {got}")
                        fails += 1
                    else:
                        va = int(got.split()[0], 16)
                        if va != want:
                            print(f"FAIL {name} 子串选优 {sub} got=0x{va:x} want=0x{want:x}")
                            fails += 1
                        else:
                            print(f"PASS {name} 0x{va:x} src=symtab-substr {sub}")
                # 阴性：错 buildid 的表不得命中（改一行 buildid）
                total += 1
                bad = td / f"{name}_bad.txt"
                lines = tbl.read_text(encoding="utf-8").splitlines()
                bad.write_text("\n".join(
                    ("deadbeef" + ln[8:]) if ln and not ln.startswith("#") else ln
                    for ln in lines) + "\n", encoding="utf-8")
                sub0 = next(iter(tblmap))
                r = subprocess.run([str(exe), str(src), sub0, str(bad)],
                                   capture_output=True, text=True)
                if r.returncode == 0 and r.stdout.strip().endswith("4"):
                    print(f"FAIL {name} buildid 不匹配却走表命中")
                    fails += 1
                else:
                    print(f"PASS {name} buildid 隔离（错表不命中）")

    # 路径 3：原件 + 无表 + 内置解码器（不装桩）→ 必须 src=3 且地址与真值一致
    env = dict(os.environ, LG_XZ_BUILTIN="1")
    for name, tblmap in SUBSTR_TRUTH.items():
        src = OUT / name
        if not src.exists():
            print(f"SKIP {name} builtin（二进制不在）")
            continue
        for sub, want in tblmap.items():
            total += 1
            r = subprocess.run([str(exe), str(src), sub, "-"],
                               capture_output=True, text=True, env=env)
            got = r.stdout.strip()
            if r.returncode != 0 or not got.startswith("0x"):
                print(f"FAIL {name} 内置解码 {sub} -> {got}")
                fails += 1
                continue
            va, srcid = int(got.split()[0], 16), int(got.split()[1])
            if va != want or srcid != 3:
                print(f"FAIL {name} 内置解码 {sub} got=0x{va:x} src={srcid} want=0x{want:x} src=3")
                fails += 1
            else:
                print(f"PASS {name} 0x{va:x} src=debugdata-builtin {sub}")

    print(f"ELFSYM_RESULT: total={total} fail={fails}")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())