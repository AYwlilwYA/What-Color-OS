#!/system/bin/sh
RB() {
  umount /system_ext/lib64/oplusex/libOplusDualHeadPhoneEx.so 2>/dev/null
  umount /system/bin/surfaceflinger 2>/dev/null
  setprop ctl.restart surfaceflinger
  echo "ROLLBACK_DONE"
}
echo "== 1 staged =="
ls -l /data/local/tmp/lg_probe.so /data/local/tmp/sf_patched 2>&1 || { echo "STAGED_MISSING"; exit 1; }
echo "== 2 bind probe =="
chcon u:object_r:system_lib_file:s0 /data/local/tmp/lg_probe.so || { echo "CHCON_PROBE_FAIL"; RB; exit 1; }
mount --bind /data/local/tmp/lg_probe.so /system_ext/lib64/oplusex/libOplusDualHeadPhoneEx.so || { echo "BIND_PROBE_FAIL"; RB; exit 1; }
echo "== 3 bind sf =="
chcon u:object_r:surfaceflinger_exec:s0 /data/local/tmp/sf_patched || { echo "CHCON_SF_FAIL"; RB; exit 1; }
chmod 755 /data/local/tmp/sf_patched
mount --bind /data/local/tmp/sf_patched /system/bin/surfaceflinger || { echo "BIND_SF_FAIL"; RB; exit 1; }
echo "== 4 restart sf =="
logcat -c
setprop ctl.restart surfaceflinger
sleep 12
echo "== 5 verify =="
NEWPID=$(pidof surfaceflinger)
echo "newpid=$NEWPID"
if [ -z "$NEWPID" ]; then echo "SF_DOWN"; RB; exit 1; fi
if grep -q lg_probe /proc/$NEWPID/maps; then echo "PROBE_IN_MAPS=yes"; else echo "PROBE_IN_MAPS=no"; fi
LOGHITS=$(logcat -d 2>/dev/null | grep -c "LG-PROBE")
echo "log_hits=$LOGHITS"
logcat -d 2>/dev/null | grep "LG-PROBE" | tail -5
if [ "$LOGHITS" -gt 0 ]; then echo "TEST_1A_PASS"; else echo "TEST_1A_NO_LOG"; fi
echo "== done =="
