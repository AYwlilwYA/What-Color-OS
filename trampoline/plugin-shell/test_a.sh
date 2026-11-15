#!/system/bin/sh
CAP=/data/local/tmp/cap_testA.txt
chcon u:object_r:surfaceflinger_exec:s0 /data/local/tmp/sf_patched || { echo "CHCON_FAIL"; exit 1; }
chmod 755 /data/local/tmp/sf_patched
mount --bind /data/local/tmp/sf_patched /system/bin/surfaceflinger || { echo "BIND_FAIL"; exit 1; }
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
  echo "TEST_A_PASS"
else
  echo "TEST_A_FAIL"
  umount /system/bin/surfaceflinger
  setprop ctl.restart surfaceflinger
  echo "ROLLBACK_DONE"
fi
echo "== done =="
