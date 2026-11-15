#!/system/bin/sh
PAT="liboplus_display_watchcall|liboplusdisplaycolormanager|libopluslooper"
for p in /proc/[0-9]*
do
  m=$(grep -E "$PAT" "$p/maps" 2>/dev/null)
  if [ -n "$m" ]
  then
    echo "P: $(cat "$p/comm" 2>/dev/null)"
    echo "$m" | grep -oE "[A-Za-z0-9_-]+\.so" | sort -u | sed 's/^/    /'
  fi
done
echo "== done =="
