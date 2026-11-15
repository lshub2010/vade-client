#!/bin/bash
# g-shadow-real · deterministic emulator navigation + screencap.
# ONLY emulator-5554. Never the physical device.
set -eu
ADB="${ADB:-/d/Android/Sdk/platform-tools/adb}"
OUT="${OUT:-shots/g-shadow-real}"
mkdir -p "$OUT"

shot() { $ADB -s emulator-5554 exec-out screencap -p > "$OUT/$1.png"; echo "wrote $OUT/$1.png"; }

foreground() { $ADB -s emulator-5554 shell dumpsys activity activities | grep -m1 topResumedActivity; }

# The emulator is slow: the resumed-activity line can lag a frame behind a tap.
# Retry for up to 10s before declaring the app gone.
assert_foreground() {
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    if foreground | grep -q "vade.ui"; then return 0; fi
    sleep 1
  done
  echo "APP NOT FOREGROUND:"; foreground; exit 1
}

relaunch() {
  $ADB -s emulator-5554 shell am force-stop vade.ui
  $ADB -s emulator-5554 shell am start -W -n vade.ui/.MainActivity >/dev/null
  sleep 6
  assert_foreground
}

case "${1:-}" in
  composer)
    # chat detail with tool cards / text scrolled BEHIND the floating composer
    relaunch
    $ADB -s emulator-5554 shell input tap 300 540
    sleep 4
    $ADB -s emulator-5554 shell input swipe 540 1500 540 2150 400
    sleep 2
    assert_foreground
    shot "$2"
    ;;
  profile)
    # clean canvas above the top bar (no scrolled content)
    relaunch
    $ADB -s emulator-5554 shell input tap 900 2245
    sleep 4
    assert_foreground
    shot "$2"
    ;;
  sessions)
    relaunch
    shot "$2"
    ;;
  *) echo "usage: $0 {composer|profile|sessions} <name>"; exit 2 ;;
esac
