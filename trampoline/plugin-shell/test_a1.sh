#!/system/bin/sh
CAP=/data/local/tmp/cap_a1.txt
chcon u:object_r:system_lib_file:s0 /data/local/tmp/scene_patched.so || { echo "CHCON_FAIL"; exit 1; }
chmod 644 /data/local/tmp/scene_patched.so
mount --bind /data/local/tmp/scene_patched.so /system_ext/lib64/liboplus_display_scene.so || { echo "BIND_FAIL"; exit 1; }
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
  if grep -q liboplus_display_scene /proc/$STABLE/maps; then echo "SCENE_IN_MAPS=yes"; else echo "SCENE_IN_MAPS=no"; fi
  echo "TEST_A1_PASS"
else
  echo "TEST_A1_FAIL"
  umount /system_ext/lib64/liboplus_display_scene.so
  setprop ctl.restart surfaceflinger
  echo "ROLLBACK_DONE"
fi
echo "== done =="
