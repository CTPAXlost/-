#!/usr/bin/env bash
set -euo pipefail

APK="${1:-app/build/outputs/apk/release/app-release.apk}"
PACKAGE="ru.elc.arcade.debug"
TAG="ELC_RUNTIME"
OUT="artifacts/runtime-test"
mkdir -p "$OUT"
: > "$OUT/runtime-transitions.txt"

if [[ ! -s "$APK" ]]; then
  echo "APK не найден: $APK" >&2
  exit 1
fi

adb wait-for-device
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true

if ! adb install -r "$APK"; then
  adb uninstall "$PACKAGE" || true
  adb install "$APK"
fi

screen_size="$(adb shell wm size | tr -d '\r' | tail -n 1 | grep -oE '[0-9]+x[0-9]+' | tail -n 1)"
W="${screen_size%x*}"
H="${screen_size#*x}"
if [[ -z "$W" || -z "$H" ]]; then
  echo "Не удалось определить размер экрана" >&2
  exit 1
fi

assert_running() {
  local stage="$1"
  local pid
  pid="$(adb shell pidof "$PACKAGE" | tr -d '\r' || true)"
  if [[ -z "$pid" ]]; then
    adb logcat -d > "$OUT/logcat-crash.txt" || true
    echo "Приложение остановилось на этапе: $stage" >&2
    exit 1
  fi
}

shot() {
  local name="$1"
  adb exec-out screencap -p > "$OUT/$name.png"
  test -s "$OUT/$name.png"
}

tap_percent() {
  local xp="$1"
  local yp="$2"
  adb shell input tap "$((W * xp / 100))" "$((H * yp / 100))"
}

start_stage() {
  adb logcat -c
}

wait_marker() {
  local marker="$1"
  local stage="$2"
  local found=""
  for _ in $(seq 1 30); do
    found="$(adb logcat -d -s "${TAG}:I" "${TAG}:E" '*:S' | tr -d '\r' || true)"
    if grep -Fq "$marker" <<<"$found"; then
      printf '\n===== %s =====\n%s\n' "$stage" "$found" >> "$OUT/runtime-transitions.txt"
      if grep -Fq "RECOVERED_RUNTIME_ERROR" <<<"$found" || grep -Fq "STARTUP_ERROR" <<<"$found"; then
        echo "Игра перехватила внутреннюю ошибку на этапе: $stage" >&2
        exit 1
      fi
      return 0
    fi
    sleep 1
  done
  printf '\n===== НЕ НАЙДЕН МАРКЕР: %s =====\n%s\n' "$stage" "$found" >> "$OUT/runtime-transitions.txt"
  adb logcat -d > "$OUT/logcat-timeout.txt" || true
  echo "Не подтверждено состояние '$marker' на этапе: $stage" >&2
  exit 1
}

start_stage
adb shell am force-stop "$PACKAGE"
adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null
wait_marker "SCREEN_MENU" "главное меню"
assert_running "главное меню"
shot "01-главное-меню"

# Александр Ложкин
start_stage
tap_percent 17 75
wait_marker "READY_LOZHKIN" "правила Александра"
shot "02-александр-правила"
start_stage
tap_percent 50 57
wait_marker "PLAYING_LOZHKIN" "игра Александра"
sleep 2
assert_running "игра Александра"
shot "03-александр-игра"
tap_percent 20 38
tap_percent 20 68
tap_percent 80 38
tap_percent 80 68
sleep 1
assert_running "управление Александра"
start_stage
adb shell input keyevent 4
wait_marker "SCREEN_MENU" "возврат после Александра"

# Алик Рак
start_stage
tap_percent 50 75
wait_marker "READY_RAK" "правила Алика"
start_stage
tap_percent 50 57
wait_marker "PLAYING_RAK" "игра Алика"
sleep 2
assert_running "игра Алика"
shot "04-алик-игра"
tap_percent 20 38
tap_percent 80 68
sleep 1
assert_running "управление Алика"
start_stage
adb shell input keyevent 4
wait_marker "SCREEN_MENU" "возврат после Алика"

# Андрей Королёв
start_stage
tap_percent 83 75
wait_marker "READY_KOROLEV" "правила Андрея"
start_stage
tap_percent 50 57
wait_marker "PLAYING_KOROLEV" "игра Андрея"
sleep 2
assert_running "игра Андрея"
shot "05-андрей-игра"
tap_percent 80 38
tap_percent 20 68
sleep 1
assert_running "управление Андрея"

adb logcat -d > "$OUT/logcat-final.txt" || true
adb shell dumpsys activity activities > "$OUT/activity.txt" || true
adb shell dumpsys package "$PACKAGE" > "$OUT/package.txt" || true

if grep -E "FATAL EXCEPTION|ANR in ${PACKAGE}|Process: ${PACKAGE}|RECOVERED_RUNTIME_ERROR|STARTUP_ERROR" "$OUT/logcat-final.txt"; then
  echo "В журнале найдено аварийное завершение или перехваченная ошибка ЕЛЦ" >&2
  exit 1
fi

assert_running "завершение smoke-теста"
echo "Smoke-тест пройден: меню и все три игровых режима реально открылись в эмуляторе."
