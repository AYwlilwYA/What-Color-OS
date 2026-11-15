#!/system/bin/sh
CAP=/data/local/tmp/cap_main.txt
echo "== 0 labels before =="
ls -Z /system_ext/lib64/oplusex/libdisplayppex.so /system_ext/lib64/oplusex/libdisplayppex.real.so /system_ext/lib64/liboplus_display_watchcall.so 2>&1
chcon u:object_r:system_lib_file:s0 /data/local/tmp/watchcall_patched.so || { echo "CHCON_FAIL"; exit 1; }
chmod 644 /data/local/tmp/watchcall_patched.so
mount --bind /data/local/tmp/watchcall_patched.so /system_ext/lib64/liboplus_display_watchcall.so || { echo "BIND_FAIL"; exit 1; }
logcat -c
setprop ctl.restart surfaceflinger
i=0
NEWPID=""
while [ $i -lt 13 ]
do
  sleep 2
  NEWPID=$(pidof surfaceflinger)
  if [ -n "$NEWPID" ]; then break; fi
  i=$((i+1))
done
echo "newpid=$NEWPID waited=$((i*2))s"
sleep 5
STABLE=$(pidof surfaceflinger)
echo "stable_pid=$STABLE"
logcat -d -b all > $CAP 2>&1
echo "cap_lines=$(grep -c '' $CAP)"
if [ -n "$STABLE" ] && [ "$STABLE" = "$NEWPID" ]; then
  echo "-- alife_count --"
  grep -c "LG-TRAMPOLINE" $CAP
  echo "-- alife_sample --"
  grep "LG-TRAMPOLINE" $CAP | tail -5
  echo "-- maps --"
  grep -E "displayppex|watchcall" /proc/$STABLE/maps
  echo "-- link_err_count --"
  grep -cE "CANNOT LINK|cannot locate" $CAP
  echo "TEST_MAIN_PASS"
else
  echo "TEST_MAIN_FAIL"
  echo "-- link_err --"
  grep -E "CANNOT LINK|cannot locate|linker64" $CAP | head -10
  umount /system_ext/lib64/liboplus_display_watchcall.so
  setprop ctl.restart surfaceflinger
  echo "ROLLBACK_DONE"
fi
echo "== done =="
