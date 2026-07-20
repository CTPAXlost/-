#!/usr/bin/env bash
set -euo pipefail

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
TAG="ELC_RUNTIME"
OUT="artifacts/runtime-test"
mkdir -p "$OUT"
: > "$OUT/runtime-transitions.txt"

fail() {
  local message="$1"
  adb logcat -d -v threadtime > "$OUT/logcat-failure.txt" 2>/dev/null || true
  adb shell dumpsys activity activities > "$OUT/activity-failure.txt" 2>/dev/null || true
  echo "$message" >&2
  exit 1
}

if [[ ! -s "$APK" ]]; then
  fail "APK не найден: $APK"
fi

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
AAPT="${SDK_ROOT}/build-tools/35.0.0/aapt"
if [[ ! -x "$AAPT" ]]; then
  AAPT="$(find "${SDK_ROOT}/build-tools" -type f -name aapt | sort -V | tail -n 1)"
fi
if [[ ! -x "$AAPT" ]]; then
  fail "Не найден aapt для определения пакета APK"
fi

"$AAPT" dump badging "$APK" > "$OUT/smoke-apk-badging.txt"
PACKAGE="$(sed -n "s/package: name='\([^']*\)'.*/\1/p" "$OUT/smoke-apk-badging.txt" | head -n 1)"
ACTIVITY="$(sed -n "s/launchable-activity: name='\([^']*\)'.*/\1/p" "$OUT/smoke-apk-badging.txt" | head -n 1)"
if [[ -z "$PACKAGE" || -z "$ACTIVITY" ]]; then
  fail "Не удалось определить пакет или стартовую Activity"
fi
COMPONENT="${PACKAGE}/${ACTIVITY}"

echo "PACKAGE=$PACKAGE" | tee -a "$OUT/runtime-transitions.txt"
echo "ACTIVITY=$ACTIVITY" | tee -a "$OUT/runtime-transitions.txt"

adb wait-for-device
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true
adb uninstall "$PACKAGE" >/dev/null 2>&1 || true
adb install -r -t "$APK" | tee "$OUT/install.txt"

screen_size="$(adb shell wm size | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -n 1)"
W="${screen_size%x*}"
H="${screen_size#*x}"
[[ "$W" =~ ^[0-9]+$ && "$H" =~ ^[0-9]+$ ]] || fail "Не удалось определить размер экрана"

shot() {
  local name="$1"
  adb exec-out screencap -p > "$OUT/$name.png"
  [[ -s "$OUT/$name.png" ]] || fail "Не удалось сохранить скриншот $name"
}

assert_no_crash() {
  local stage="$1"
  local logs
  logs="$(adb logcat -d -v brief | tr -d '\r' || true)"
  printf '\n===== LOGCAT %s =====\n%s\n' "$stage" "$logs" >> "$OUT/runtime-transitions.txt"
  if grep -E "FATAL EXCEPTION|ANR in ${PACKAGE}|Process: ${PACKAGE}|STARTUP_ERROR|RECOVERED_RUNTIME_ERROR" <<<"$logs"; then
    fail "Обнаружено аварийное завершение на этапе: $stage"
  fi
}

wait_for_state() {
  local marker="$1"
  local stage="$2"
  local logs=""
  local pid=""
  for _ in $(seq 1 40); do
    pid="$(adb shell pidof "$PACKAGE" | tr -d '\r' || true)"
    logs="$(adb logcat -d -v brief | grep -F "$TAG" | tr -d '\r' || true)"
    if [[ -n "$pid" ]] && grep -Fq "$marker" <<<"$logs"; then
      printf '\n===== STATE %s =====\nPID=%s\n%s\n' "$stage" "$pid" "$logs" >> "$OUT/runtime-transitions.txt"
      assert_no_crash "$stage"
      return 0
    fi
    sleep 1
  done
  printf '\n===== STATE TIMEOUT %s =====\nPID=%s\n%s\n' "$stage" "$pid" "$logs" >> "$OUT/runtime-transitions.txt"
  fail "Не подтверждено состояние '$marker' на этапе: $stage"
}

launch_state() {
  local command="$1"
  local marker="$2"
  local stage="$3"
  local screenshot="$4"
  adb logcat -c
  adb shell am force-stop "$PACKAGE" || true
  adb shell am start -W -S -n "$COMPONENT" --es elc_smoke "$command" | tee "$OUT/start-${command}.txt"
  wait_for_state "$marker" "$stage"
  shot "$screenshot"
}

launch_state "menu" "SCREEN_MENU" "главное меню" "01-главное-меню"
launch_state "lozhkin" "PLAYING_LOZHKIN" "игра Александра" "02-александр-игра"
adb shell input tap "$((W * 20 / 100))" "$((H * 38 / 100))"
adb shell input tap "$((W * 80 / 100))" "$((H * 68 / 100))"
sleep 1
assert_no_crash "управление Александра"

launch_state "rak" "PLAYING_RAK" "игра Алика" "03-алик-игра"
adb shell input tap "$((W * 20 / 100))" "$((H * 38 / 100))"
adb shell input tap "$((W * 80 / 100))" "$((H * 68 / 100))"
sleep 1
assert_no_crash "управление Алика"

launch_state "korolev" "PLAYING_KOROLEV" "игра Андрея" "04-андрей-игра"
adb shell input tap "$((W * 80 / 100))" "$((H * 38 / 100))"
adb shell input tap "$((W * 20 / 100))" "$((H * 68 / 100))"
sleep 1
assert_no_crash "управление Андрея"

adb shell dumpsys package "$PACKAGE" > "$OUT/package.txt" || true
adb shell dumpsys activity activities > "$OUT/activity-final.txt" || true
adb logcat -d -v threadtime > "$OUT/logcat-final.txt" || true

echo "Smoke-тест пройден: меню и три игровых режима открылись без падения."
