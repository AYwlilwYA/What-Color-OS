#!/bin/bash
set -e
NDK=/mnt/f/ikenrel/nh/android-ndk-r27d
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
SYSROOT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
T=/mnt/f/_Android/ColorOS16-LiquidGlass/trampoline/plugin-shell
"$CC" --target=aarch64-linux-android31 --sysroot="$SYSROOT" \
  -shared -fPIC -O2 -Wl,-soname,lg_probe.so \
  "$T/probe.c" -llog -o "$T/lg_probe.so"
D=$(readelf -d "$T/lg_probe.so")
echo "$D" | grep -q 'SONAME.*\[lg_probe.so\]' || { echo "ASSERT-FAIL soname"; exit 1; }
readelf --dyn-syms -W "$T/lg_probe.so" | awk '$5=="GLOBAL" && $6=="DEFAULT" && $7!="UND"' | grep -q . && {
  echo "ASSERT-FAIL exported symbols:"; exit 1; } || true
echo "BUILD_OK"
ls -l "$T/lg_probe.so"
