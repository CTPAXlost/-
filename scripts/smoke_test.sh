#!/usr/bin/env bash
set -euo pipefail

APK="${1:-app/build/outputs/apk/release/app-release.apk}"
PACKAGE="ru.elc.arcade.debug"
OUT="artifacts/runtime-test"
mkdir -p "$OUT"

if [[ ! -s "$APK" ]]; then
  echo "APK не найден: $APK" >&2
  exit 1
fi

adb wait-for-device
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true

# Подпись прошлой тестовой сборки могла отличаться. В чистом эмуляторе это не мешает,
# а на повторном запуске сначала пробуем обновление, затем чистую установку.
if ! adb install -r "$APK"; then
  adb uninstall "$PACKAGE" || true
  adb install "$APK"
fi

adb logcat -c
adb shell am force-stop "$PACKAGE"
adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 4

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
    adb logcat -d > "$OUT/logcat.txt" || true
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
  local x=$((W * xp / 100))
  local y=$((H * yp / 100))
  adb shell input tap "$x" "$y"
}

assert_running "главное меню"
shot "01-главное-меню"

# Александр Ложкин
# Кнопки персонажей находятся примерно на 75% высоты и в трёх колонках.
tap_percent 17 75
sleep 2
assert_running "экран правил Александра"
shot "02-александр-правила"
tap_percent 50 57
sleep 5
assert_running "игра Александра"
shot "03-александр-игра"
tap_percent 20 38
tap_percent 20 68
tap_percent 80 38
tap_percent 80 68
sleep 2
assert_running "управление Александра"
adb shell input keyevent 4
sleep 2

# Алик Рак
tap_percent 50 75
sleep 2
assert_running "экран правил Алика"
tap_percent 50 57
sleep 5
assert_running "игра Алика"
shot "04-алик-игра"
tap_percent 20 38
tap_percent 80 68
sleep 2
assert_running "управление Алика"
adb shell input keyevent 4
sleep 2

# Андрей Королёв
tap_percent 83 75
sleep 2
assert_running "экран правил Андрея"
tap_percent 50 57
sleep 5
assert_running "игра Андрея"
shot "05-андрей-игра"
tap_percent 80 38
tap_percent 20 68
sleep 2
assert_running "управление Андрея"

adb logcat -d > "$OUT/logcat.txt"
adb shell dumpsys activity activities > "$OUT/activity.txt" || true
adb shell dumpsys package "$PACKAGE" > "$OUT/package.txt" || true

if grep -E "FATAL EXCEPTION|ANR in ${PACKAGE}|Process: ${PACKAGE}" "$OUT/logcat.txt"; then
  echo "В журнале найдено аварийное завершение ЕЛЦ" >&2
  exit 1
fi

assert_running "завершение smoke-теста"
echo "Smoke-тест пройден: меню и все три игровых режима запускаются без падения."
