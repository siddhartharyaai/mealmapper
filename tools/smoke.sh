#!/usr/bin/env bash
# Emulator smoke test: install an APK, open Home, tap each enabled entry point, and fail on any crash.
# Usage: tools/smoke.sh path/to/app.apk
set -u
APK="$1"
PKG=app.mealmapper
OUT=smoke-out
mkdir -p "$OUT"

tap_text() {
  # Find a node by its visible text in the UI dump and tap its centre.
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
  python3 - "$1" "$OUT/ui.xml" <<'PY' > "$OUT/tap.txt"
import re, sys
text, path = sys.argv[1], sys.argv[2]
xml = open(path, encoding="utf-8").read()
for node in re.finditer(r'<node [^>]*>', xml):
    n = node.group(0)
    if f'text="{text}"' in n:
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
  if [ ! -s "$OUT/tap.txt" ]; then echo "Not on screen: $1"; return 1; fi
  adb shell input tap $(cat "$OUT/tap.txt")
}

crashed() {
  adb logcat -d -b crash > "$OUT/crash.txt" 2>/dev/null
  grep -q "$PKG" "$OUT/crash.txt" || return 1
  # Release builds are obfuscated by R8. Decode the stack trace with the mapping file when we have it.
  local mapping retrace
  mapping=$(find . -path '*mapping/release/mapping.txt' -o -name mapping.txt 2>/dev/null | head -1)
  retrace=$(ls "$ANDROID_HOME"/cmdline-tools/*/bin/retrace 2>/dev/null | head -1)
  if [ -n "$mapping" ] && [ -n "$retrace" ]; then
    sed -E 's/^.*AndroidRuntime: //' "$OUT/crash.txt" | "$retrace" "$mapping" > "$OUT/crash-retraced.txt" 2>&1 \
      && cp "$OUT/crash-retraced.txt" "$OUT/crash.txt"
  fi
  return 0
}

run() {
  local label="$1" grant="$2"
  echo "=== $label ==="
  adb uninstall "$PKG" >/dev/null 2>&1
  if [ "$grant" = yes ]; then adb install -g "$APK"; else adb install "$APK"; fi
  adb logcat -c; adb logcat -b crash -c
  adb shell am start -W -n "$PKG/.MainActivity"
  sleep 6
  if crashed; then echo "CRASH on start ($label)"; cat "$OUT/crash.txt"; return 1; fi
  adb shell screencap -p /sdcard/home.png; adb pull /sdcard/home.png "$OUT/$label-home.png" >/dev/null
  tap_text "Scan barcode" || return 1
  sleep 8
  adb shell screencap -p /sdcard/scan.png; adb pull /sdcard/scan.png "$OUT/$label-scan.png" >/dev/null
  if crashed; then echo "CRASH on Scan ($label)"; cat "$OUT/crash.txt"; return 1; fi
  echo "OK: $label"
}

status=0
run camera-granted yes || status=1
run camera-not-granted no || status=1
adb logcat -d > "$OUT/logcat.txt"
exit $status
