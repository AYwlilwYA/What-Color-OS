#!/system/bin/sh
echo "== A modules =="
ls /data/adb/modules/ 2>/dev/null
echo "-- state --"
cat /data/adb/modules/lg_sf_channel/state 2>/dev/null || echo "NO_STATE"
echo "== B oplusex =="
ls -l /system_ext/lib64/oplusex/ 2>/dev/null
echo "== C sha slot =="
sha256sum /system_ext/lib64/oplusex/libdisplayppex.so 2>/dev/null
echo "== D scene =="
ls -l /system_ext/lib64/liboplus_display_scene.so 2>/dev/null
echo "== E sf maps =="
SFPID=$(pidof surfaceflinger)
echo "sfpid=$SFPID"
grep -E "displayppex|oplus_display_scene|oplusplugin" /proc/$SFPID/maps 2>/dev/null
echo "== F attr =="
getprop debug.sf.load_dpp_lib
echo "== done =="
