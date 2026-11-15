#!/system/bin/sh
CAP=/data/local/tmp/cap_a1attr.txt
echo "== 1 bind cmp =="
if cmp -s /system_ext/lib64/liboplus_display_scene.so /data/local/tmp/scene_patched.so; then echo "CMP_OK=bind生效"; else echo "CMP_MISMATCH"; fi
echo "== 2 关原厂链 + 重启 =="
setprop debug.sf.load_dpp_lib 0
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
  if grep -q displayppex /proc/$STABLE/maps; then echo "DISPLAYPPEX_IN_MAPS=yes"; else echo "DISPLAYPPEX_IN_MAPS=no"; fi
  H1=$(grep -c "Do not need to open libdisplayppex" $CAP)
  echo "gatelogs_off=$H1"
  echo "TEST_ATTR_PASS"
else
  echo "TEST_ATTR_FAIL"
  setprop debug.sf.load_dpp_lib 1
  umount /system_ext/lib64/liboplus_display_scene.so
  setprop ctl.restart surfaceflinger
  echo "ROLLBACK_DONE"
fi
setprop debug.sf.load_dpp_lib 1
echo "== attr restored =="
echo "== done =="
