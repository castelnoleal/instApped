#!/usr/bin/env bash
# Runs inside the Android emulator job of local-files-test.yml.
set -u
APK=generated-app/app/build/outputs/apk/debug/app-debug.apk
PKG=com.castel.filestest
OUT=files-test/out
mkdir -p "$OUT"
dump() {
  sleep 2
  adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
  grep -E "FILES_TEST:|FATAL|AndroidRuntime|CastelAppFactory" "$OUT/logcat.txt" | tail -n 60 || true
  grep -E "FILES_TEST:FAIL|FATAL|E AndroidRuntime" "$OUT/logcat.txt" | tail -n 9 | cut -c1-400 | while IFS= read -r l; do echo "::error title=logcat::$l"; done
  adb exec-out screencap -p > "$OUT/last-screen.png" 2>/dev/null || true
}
fail() { echo "::error title=Library folders::$1"; dump; exit 1; }
wait_log() { # wait_log <pattern> <seconds>
  for _ in $(seq 1 "$2"); do
    if adb logcat -d 2>/dev/null | grep -F "$1" >/dev/null; then return 0; fi
    if adb logcat -d 2>/dev/null | grep -F "FILES_TEST:FAIL" >/dev/null; then return 1; fi
    sleep 1
  done
  return 1
}
timeout 180 adb wait-for-device || { echo "::error title=Emulator::device did not come online"; exit 1; }
for _ in 1 2 3 4 5 6 7 8 9 10; do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break; sleep 5
done
for _ in $(seq 1 30); do adb shell ls /sdcard/ >/dev/null 2>&1 && break; sleep 3; done
adb install -r "$APK" || { sleep 20; adb install -r "$APK"; } || fail "adb install failed"
adb logcat -c
adb shell am start -n "$PKG/.MainActivity" >/dev/null

wait_log "FILES_TEST:INFO:app" 60 || fail "window.CastelFiles is missing or not using the app folder"
wait_log "FILES_TEST:TEXT_OK" 20 || fail "writing and reading a text file failed"
wait_log "FILES_TEST:SERVE_OK" 20 || fail "a saved picture was not served to the page"
wait_log "FILES_TEST:LIST_OK" 20 || fail "listing the folder failed"
wait_log "FILES_TEST:FRAME_OK" 20 || fail "a framed page could not load a saved file"
wait_log "FILES_TEST:DOWNLOAD_OK" 90 || fail "the native background download failed"
wait_log "FILES_TEST:REMOVE_OK" 20 || fail "removing a folder failed"
wait_log "FILES_TEST:READY_PASTE" 10 || fail "driver did not reach the paste step"

# A person pastes a picture into the folder with a file manager (or over USB).
python3 - <<'PY'
import base64
open("/tmp/20000.png", "wb").write(base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg=="))
PY
DIR="/sdcard/Android/data/$PKG/files/Lib/pics"
adb shell ls "$DIR" > "$OUT/folder-before.txt" 2>&1 || true
adb push /tmp/20000.png "$DIR/20000.png" || fail "could not copy a file into the app folder"
wait_log "FILES_TEST:PASTE_OK" 70 || fail "a file pasted into the folder was not picked up"
wait_log "FILES_TEST:DONE" 10 || fail "driver did not finish"
adb exec-out screencap -p > "$OUT/1-done.png"
adb logcat -d > "$OUT/logcat.txt" || true
grep -E "FATAL EXCEPTION" "$OUT/logcat.txt" && fail "the app crashed"
echo "Library folders acceptance passed."
