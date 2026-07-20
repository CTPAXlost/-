#!/usr/bin/env bash
set -euo pipefail

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
PACKAGE="ru.elc.arcade.debug"
ACTIVITY="ru.elc.arcade.MainActivity"
OUT="artifacts/runtime-test"
mkdir -p "$OUT"

adb wait-for-device
adb install -r "$APK"

SIZE_LINE="$(adb shell wm size | tr -d '\r' | tail -n 1)"
SIZE="${SIZE_LINE##*: }"
W="${SIZE%x*}"
H="${SIZE#*x}"
if ! [[ "$W" =~ ^[0-9]+$ && "$H" =~ ^[0-9]+$ ]]; then
  W=1080
  H=1920
fi

start_app() {
  local name="$1"
  adb shell am force-stop "$PACKAGE" || true
  adb logcat -c
  adb shell am start -W -n "$PACKAGE/$ACTIVITY" | tee "$OUT/am-start-$name.txt"
  sleep 3
  adb shell pidof "$PACKAGE" >/dev/null
  adb shell dumpsys activity activities > "$OUT/activity-$name.txt"
  grep -q "$PACKAGE" "$OUT/activity-$name.txt"
}

save_log() {
  local name="$1"
  adb logcat -d > "$OUT/logcat-$name.txt"
}

screenshot() {
  local name="$1"
  adb exec-out screencap -p > "$OUT/$name.png"
}

tap_percent() {
  local xp="$1"
  local yp="$2"
  local x=$(( W * xp / 100 ))
  local y=$(( H * yp / 100 ))
  adb shell input tap "$x" "$y"
}

assert_state() {
  local log="$1"
  local expected="$2"
  grep -Fq "ELC_STATE $expected" "$log"
}

start_app menu
sleep 1
save_log menu
screenshot menu
assert_state "$OUT/logcat-menu.txt" "ELC|MENU|LOZHKIN|LEVEL=1"

run_hero() {
  local name="$1"
  local xpercent="$2"
  local state="$3"

  start_app "$name-menu"
  tap_percent "$xpercent" 55
  sleep 4
  save_log "$name-playing"
  screenshot "$name-playing"
  assert_state "$OUT/logcat-$name-playing.txt" "ELC|PLAYING|$state|LEVEL=1"

  tap_percent 20 34
  tap_percent 20 65
  tap_percent 80 34
  tap_percent 80 65
  sleep 2
  adb shell pidof "$PACKAGE" >/dev/null
  save_log "$name-after-actions"
  screenshot "$name-after-actions"

  if grep -E "FATAL EXCEPTION|Process: ${PACKAGE}" "$OUT/logcat-$name-after-actions.txt"; then
    echo "Обнаружено аварийное завершение приложения" >&2
    exit 1
  fi
}

run_hero lozhkin 16 LOZHKIN
run_hero rak 50 RAK
run_hero korolev 84 KOROLEV

echo "SMOKE_TEST_OK" | tee "$OUT/result.txt"
