#!/system/bin/sh
PAT="libOplusDualHeadPhoneEx"
for p in /proc/[0-9]*
do
  if grep -q "$PAT" "$p/maps" 2>/dev/null
  then
    echo "P: $(cat "$p/comm" 2>/dev/null)"
  fi
done
echo "== done =="
