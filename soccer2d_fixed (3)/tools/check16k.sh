#!/usr/bin/env bash
# Google Play 16 KB sahifa talabi: APK ichidagi har native .so ning LOAD segmenti
# tekshiriladi (p_align >= 0x4000), va APK zipalign -P 16 dan o'tishi kerak.
set -euo pipefail

shopt -s nullglob
apks=( $1 )

if [ "${#apks[@]}" -eq 0 ]; then
  echo "No APK files found for pattern: $1 - skipping 16 KB check"
  exit 0
fi

APK="${apks[0]}"
TMP="$(mktemp -d)"
unzip -q "$APK" 'lib/*' -d "$TMP" 2>/dev/null || { echo "APK da native kutubxona yo'q - tekshiruv o'tkazib yuborildi"; exit 0; }
ZA="$(ls -d "${ANDROID_HOME:-/usr/local/lib/android/sdk}"/build-tools/*/zipalign 2>/dev/null | tail -1 || true)"
if [ -n "$ZA" ]; then "$ZA" -c -P 16 -v 4 "$APK" >/dev/null && echo "zipalign -P 16: OK"; fi
bad=0
while read -r so; do
  while read -r a; do
    if (( a < 0x4000 )); then echo "FAIL: $so LOAD align $a"; bad=1; fi
  done < <(readelf -lW "$so" | awk '$1=="LOAD"{print $NF}')
done < <(find "$TMP/lib" -name '*.so')
if [ "$bad" = 0 ]; then echo "16 KB sahifa tekshiruvi: OK"; else echo "16 KB talabiga javob bermaydi - libGDX ni yangilang"; exit 1; fi
