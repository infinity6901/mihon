#!/usr/bin/env bash
set -euo pipefail

APK="${1:?Usage: device-tests.sh <apk> <out-dir> [cycles]}"
OUT_DIR="${2:-device-out}"
CYCLES="${3:-5}"
PKG="app.mihon.dev"
MAIN_ACTIVITY="${PKG}/eu.kanade.tachiyomi.ui.main.MainActivity"

mkdir -p "$OUT_DIR"
export CI_LOG_DIR="$OUT_DIR/ci-logs"
export CI_STATUS_DIR="$OUT_DIR/ci-status"
source .github/scripts/ci-step.sh

echo "Waiting for emulator..."
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 2
done
adb shell input keyevent 82 || true
adb logcat -c || true

ci_step install "Install Mihon debug APK ($PKG)" adb install -r -g "$APK"

ci_step cold-launch "Cold launch MainActivity and verify process stays alive" bash -c "
  set -euo pipefail
  adb shell am start -W -n '$MAIN_ACTIVITY'
  sleep 4
  adb shell pidof '$PKG'
"

ci_step nfc-deeplinks "Exercise mihon://nfc/* deep-link actions (continue, incognito, profiles, search)" bash -c "
  set -euo pipefail
  for uri in \
    'mihon://nfc/incognito?mode=on' \
    'mihon://nfc/incognito?mode=off' \
    'mihon://nfc/profile?name=night' \
    'mihon://nfc/profile?name=commute' \
    'mihon://nfc/profile?name=home' \
    'mihon://nfc/search?q=Berserk' \
    'mihon://nfc/continue'; do
    echo \"Triggering \$uri\"
    adb shell am start -W -a android.intent.action.VIEW -d \"\$uri\" '$PKG'
    sleep 2
    adb shell pidof '$PKG' > /dev/null
  done
"

ci_step lifecycle-cycles "Run $CYCLES background/foreground, rotation, and lock/unlock cycles" bash -c "
  set -euo pipefail
  adb shell settings put system accelerometer_rotation 0
  for i in \$(seq 1 '$CYCLES'); do
    echo \"Cycle \$i/$CYCLES\"
    adb shell input keyevent KEYCODE_HOME
    sleep 1
    adb shell am start -W -n '$MAIN_ACTIVITY' > /dev/null
    sleep 1
    adb shell settings put system user_rotation \$((i % 2))
    sleep 1
    adb shell input keyevent KEYCODE_SLEEP
    sleep 1
    adb shell input keyevent KEYCODE_WAKEUP
    adb shell input keyevent 82
    sleep 1
    adb shell pidof '$PKG' > /dev/null
  done
  adb shell settings put system user_rotation 0
"

ci_step process-restart "Force-stop and relaunch Mihon cleanly" bash -c "
  set -euo pipefail
  adb shell am force-stop '$PKG'
  sleep 1
  adb shell am start -W -n '$MAIN_ACTIVITY'
  sleep 3
  adb shell pidof '$PKG'
"

# Capture logcat and screenshot before checking for crashes/ANRs
adb logcat -d > "$OUT_DIR/mihon-logcat.txt" || true
adb logcat -d -b crash > "$OUT_DIR/logcat-crash.txt" || true
adb exec-out screencap -p > "$OUT_DIR/final-screen.png" || true

ci_step crash-anr-check "Verify zero fatal exceptions or ANRs for $PKG in logcat" python3 - "$OUT_DIR/mihon-logcat.txt" "$OUT_DIR/logcat-crash.txt" "$PKG" <<'PY'
import sys
from pathlib import Path

logcat = Path(sys.argv[1]).read_text(errors="replace")
crash = Path(sys.argv[2]).read_text(errors="replace")
pkg = sys.argv[3]

hits = []
for line in (logcat + "\n" + crash).splitlines():
    if ("FATAL EXCEPTION" in line or "ANR in " in line) and (pkg in line or "eu.kanade.tachiyomi" in line):
        hits.append(line)

if hits:
    print("Crash/ANR lines detected:")
    print("\n".join(hits[:20]))
    sys.exit(1)
print(f"No fatal exceptions or ANRs detected for {pkg}.")
PY
