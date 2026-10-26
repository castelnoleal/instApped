#!/usr/bin/env bash
# Runs inside the Android emulator job of media-player-test.yml.
set -u
fail() { echo "::error title=Music player::$1"; dump; exit 1; }
APK=generated-app/app/build/outputs/apk/debug/app-debug.apk
PKG=com.castel.mediatest
OUT=media-test/out
mkdir -p "$OUT"
dump() {
  sleep 3
  adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
  grep -E "MEDIA_TEST:|FATAL|AndroidRuntime|CastelAppFactory" "$OUT/logcat.txt" | grep -v "MEDIA_TEST:POS" | tail -n 60 || true
  # Surface the important lines as annotations (readable without downloading logs).
  grep -E "CastelAppFactory|FATAL|E AndroidRuntime" "$OUT/logcat.txt" | grep -vE "MEDIA_TEST:POS|PAGE_|WEBVIEW_READY" | tail -n 9 | cut -c1-400 \
    | while IFS= read -r l; do echo "::error title=logcat::$l"; done
  adb exec-out screencap -p > "$OUT/last-screen.png" 2>/dev/null || true
}
wait_log() { # wait_log <pattern> <seconds>
  for _ in $(seq 1 "$2"); do
    if adb logcat -d 2>/dev/null | grep -F "$1" >/dev/null; then return 0; fi
    if adb logcat -d 2>/dev/null | grep -F "MEDIA_TEST:FAIL" >/dev/null; then return 1; fi
    sleep 1
  done
  return 1
}
timeout 180 adb wait-for-device || { echo "::error title=Emulator::device did not come online"; exit 1; }
for _ in 1 2 3 4 5 6 7 8 9 10; do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break; sleep 5
done
adb shell cmd package list packages >/dev/null 2>&1 || { adb kill-server; adb start-server; timeout 120 adb wait-for-device; sleep 10; }

# Songs on the "phone", like a user's music library.
adb shell mkdir -p /sdcard/Music
for f in media-test/songs/*; do adb push "$f" /sdcard/Music/ >/dev/null || fail "push $f failed"; done
adb shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1 || true
for f in media-test/songs/*; do adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Music/$(basename "$f")" >/dev/null 2>&1 || true; done
for _ in $(seq 1 60); do
  n=$(adb shell content query --uri content://media/external/audio/media --projection _display_name 2>/dev/null | grep -c "pulse-test" || true)
  [ "${n:-0}" -ge 3 ] && break; sleep 2
done
echo "MediaStore sees ${n:-0} test songs"
[ "${n:-0}" -ge 3 ] || fail "MediaStore never indexed the test songs"

adb install -r "$APK" || { sleep 20; adb install -r "$APK"; } || fail "adb install failed"
adb shell pm grant "$PKG" android.permission.READ_MEDIA_AUDIO || fail "could not grant READ_MEDIA_AUDIO"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb logcat -c
adb shell am start -n "$PKG/.MainActivity" >/dev/null

wait_log "MEDIA_TEST:SCAN_OK" 90 || fail "the app did not find the device's songs automatically"
wait_log "MEDIA_TEST:PLAY_OK" 40 || fail "a device song did not start playing"
wait_log "MEDIA_TEST:SEEK_OK" 20 || fail "seeking inside a device song failed (HTTP range support)"
wait_log "MEDIA_TEST:NEXT_OK" 25 || fail "Next did not start the next song"
wait_log "MEDIA_TEST:AUTONEXT_OK" 25 || fail "the player did not move on when a song ended"
wait_log "MEDIA_TEST:READY_BG" 10 || fail "driver did not finish"
adb exec-out screencap -p > "$OUT/1-app-playing.png"

# Notification + media session.
sleep 2
adb shell dumpsys media_session > "$OUT/media_session.txt"
grep -q "$PKG" "$OUT/media_session.txt" || fail "no media session registered"
grep -E "state=PlaybackState \{state=(3|PLAYING)" "$OUT/media_session.txt" >/dev/null || fail "media session is not in the playing state"
adb shell dumpsys notification --noredact > "$OUT/notifications.txt"
grep -q "castel_media" "$OUT/notifications.txt" || fail "no media notification posted"
adb shell cmd statusbar expand-notifications; sleep 3
adb exec-out screencap -p > "$OUT/2-notification-shade.png"
adb shell cmd statusbar collapse; sleep 1

# Background playback: leave the app and confirm the song keeps going.
adb logcat -c
adb shell input keyevent KEYCODE_HOME
sleep 15
adb logcat -d | grep "MEDIA_TEST:POS" > "$OUT/background-pos.txt" || true
echo "Background positions:"; tail -n 5 "$OUT/background-pos.txt"
lines=$(grep -c ":playing:" "$OUT/background-pos.txt" || true)
[ "${lines:-0}" -ge 8 ] || fail "music stopped when the app went to the background (${lines:-0} playing ticks)"
python3 - "$OUT/background-pos.txt" <<'PY' || fail "background position did not advance"
import sys, re
pos = []
for line in open(sys.argv[1]):
    m = re.search(r"POS:(.*?):([\d.]+):playing:", line)
    if m: pos.append((m.group(1), float(m.group(2))))
moved = sum(1 for a, b in zip(pos, pos[1:]) if a[0] != b[0] or b[1] > a[1])
print("advancing ticks:", moved)
sys.exit(0 if moved >= 6 else 1)
PY

# Lock screen / headset / notification controls go through the media session.
adb logcat -c
adb shell cmd media_session dispatch next >/dev/null 2>&1 || adb shell media dispatch next >/dev/null 2>&1 || true
wait_log "MEDIA_TEST:EVENT:next" 10 || fail "the Next control did not reach the player"
adb shell cmd media_session dispatch pause >/dev/null 2>&1 || adb shell media dispatch pause >/dev/null 2>&1 || true
wait_log "MEDIA_TEST:EVENT:pause" 10 || fail "the Pause control did not reach the player"
sleep 3
adb logcat -d | grep "MEDIA_TEST:POS" | tail -n 1 | grep -q ":paused:" || fail "player did not pause from the media control"
adb shell dumpsys media_session | grep -E "state=PlaybackState \{state=(2|PAUSED)" >/dev/null || fail "media session did not show paused"
adb logcat -c
adb shell cmd media_session dispatch play >/dev/null 2>&1 || adb shell media dispatch play >/dev/null 2>&1 || true
wait_log "MEDIA_TEST:EVENT:play" 10 || fail "the Play control did not reach the player"
sleep 3
adb logcat -d | grep "MEDIA_TEST:POS" | tail -n 1 | grep -q ":playing:" || fail "player did not resume from the media control (app in background)"
adb shell cmd statusbar expand-notifications; sleep 3
adb exec-out screencap -p > "$OUT/3-notification-background.png"
adb shell cmd statusbar collapse

# Back to the app; still alive and playing.
adb shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 3
adb exec-out screencap -p > "$OUT/4-back-in-app.png"
adb logcat -d > "$OUT/logcat.txt" || true
grep -E "FATAL EXCEPTION" "$OUT/logcat.txt" && fail "the app crashed"
echo "Music player acceptance passed."
