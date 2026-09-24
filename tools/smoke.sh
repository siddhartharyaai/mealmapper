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
  if [ ! -s "$OUT/tap.txt" ]; then echo "Not on screen: $1"; show_screen; return 1; fi
  adb shell input tap $(cat "$OUT/tap.txt")
}

show_screen() {
  # What is on screen, for the CI log (screenshots need a GitHub login to view).
  echo "  screen texts: $(grep -o 'text="[^"]\+"' "$OUT/ui.xml" | sed 's/text=//' | head -40 | tr '\n' ' ')"
  grep -o 'package="[^"]*"' "$OUT/ui.xml" | sort -u | head -3 | sed 's/^/  /'
}

tap_contains() {
  # Like tap_text, but matches part of the text (long names are cut on small screens).
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
  python3 - "$1" "$OUT/ui.xml" <<'PY' > "$OUT/tap.txt"
import re, sys
text, path = sys.argv[1], sys.argv[2]
xml = open(path, encoding="utf-8").read()
for node in re.finditer(r'<node [^>]*>', xml):
    n = node.group(0)
    m = re.search(r'text="([^"]*)"', n)
    if m and text in m.group(1):
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
  if [ ! -s "$OUT/tap.txt" ]; then echo "Not on screen: $1"; return 1; fi
  adb shell input tap $(cat "$OUT/tap.txt")
}

swipe_pct() {
  # Swipe between two heights given in percent of the screen (the emulator's size varies).
  local size w h
  size=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)
  w=${size%x*}; h=${size#*x}
  adb shell input swipe $((w / 2)) $((h * $1 / 100)) $((w / 2)) $((h * $2 / 100)) 400
}

tap_home() {
  # Home scrolls: go to the top, then look for the tile, scrolling down a little at a time.
  swipe_pct 30 85; swipe_pct 30 85
  sleep 1
  for _ in 1 2 3 4; do
    tap_text "$1" && return 0
    swipe_pct 75 45; sleep 1
  done
  return 1
}

on_screen() {
  # The text may be below the fold on a small emulator: scroll down a little between looks.
  for _ in 1 2 3; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
    grep -q "$1" "$OUT/ui.xml" && return 0
    swipe_pct 75 45; sleep 1
  done
  echo "Expected on screen: $1"; show_screen; return 1
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
  # Back to Home. Without camera permission the first Back only closes the permission dialog.
  for _ in 1 2 3; do
    adb shell input keyevent 4; sleep 3
    tap_text "Settings" && break
  done
  [ -s "$OUT/tap.txt" ] || return 1
  sleep 4
  if crashed; then echo "CRASH on Settings ($label)"; cat "$OUT/crash.txt"; return 1; fi
  adb shell input keyevent 4; sleep 3
  tap_home "Upload a photo" || return 1
  sleep 4
  if crashed; then echo "CRASH on Upload ($label)"; cat "$OUT/crash.txt"; return 1; fi
  adb shell input keyevent 4; sleep 3
  tap_home "Take a photo" || return 1
  sleep 4
  if crashed; then echo "CRASH on Take a photo ($label)"; cat "$OUT/crash.txt"; return 1; fi
  adb shell input keyevent 4; sleep 3
  tap_home "History" || return 1
  sleep 4
  if crashed; then echo "CRASH on History ($label)"; cat "$OUT/crash.txt"; return 1; fi
  # Offline databank: search "roti", open it, set 2 rotis with the count control. No network needed.
  adb shell input keyevent 4; sleep 3
  tap_home "Search foods" || return 1
  sleep 3
  tap_text "Food" || return 1
  adb shell input text roti; sleep 4
  adb shell input keyevent 111; sleep 1   # hide the keyboard
  adb shell screencap -p /sdcard/search.png; adb pull /sdcard/search.png "$OUT/$label-search.png" >/dev/null
  if crashed; then echo "CRASH on Search ($label)"; cat "$OUT/crash.txt"; return 1; fi
  found=""
  for _ in 1 2 3 4; do
    tap_contains "Chapati/Roti" && { found=1; break; }
    swipe_pct 80 45; sleep 1
  done
  [ -n "$found" ] || { show_screen; return 1; }
  sleep 4
  if crashed; then echo "CRASH on databank Review ($label)"; cat "$OUT/crash.txt"; return 1; fi
  on_screen "Nutrition facts" || return 1
  on_screen "How much did you eat" || return 1
  swipe_pct 70 45; sleep 2
  tap_text "+" && sleep 1 && tap_text "+" && sleep 2
  adb shell screencap -p /sdcard/review.png; adb pull /sdcard/review.png "$OUT/$label-review.png" >/dev/null
  on_screen "70 g\|2 rotis\|rotis" || return 1
  if crashed; then echo "CRASH on count control ($label)"; cat "$OUT/crash.txt"; return 1; fi
  # Voice without a key shows guidance, not a crash.
  # Back to Home: Review -> Search -> Home (a keyboard may take one extra Back).
  for _ in 1 2 3; do
    adb shell input keyevent 4; sleep 3
    tap_home "Say or type" && break
  done
  [ -s "$OUT/tap.txt" ] || return 1
  sleep 3
  on_screen "Set from the time" || return 1   # meal chips, pre-selected from the clock
  on_screen "Deepgram" || return 1
  if crashed; then echo "CRASH on Say or type ($label)"; cat "$OUT/crash.txt"; return 1; fi
  echo "OK: $label"
}

status=0
run camera-granted yes || status=1
run camera-not-granted no || status=1
adb logcat -d > "$OUT/logcat.txt"
exit $status
