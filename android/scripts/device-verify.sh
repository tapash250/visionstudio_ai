#!/usr/bin/env bash
# On-device checks for the Room v3 upgrade, Keystore resilience and video-job cancellation.
#
#   ./device-verify.sh upgrade   # Room v2 -> v3 in-place upgrade keeps rows and the saved key
#   ./device-verify.sh locked    # a cold start behind the lock screen never wipes the key
#   ./device-verify.sh preempt   # an OS stop (network lost / quota) never cancels the render remotely
#   ./device-verify.sh cancel    # a user Cancel does reach the provider, and the work is removed
#
# Setup, once:
#   - Connect the device over USB, not adb-over-Wi-Fi: `preempt` turns Wi-Fi off.
#   - Start the mock provider on this computer, in the directory you run this script from (both
#     use ./mock_provider.log.jsonl), and map it into the device:
#         python3 mock_provider.py --port 8765 &
#         adb reverse tcp:8765 tcp:8765
#   - In the app's Settings, for both Imagine and Motion: Protocol REST, Dialect "Custom JSON",
#     endpoint http://localhost:8765/v1/generate, model "mock", Auth Bearer, key "sk-device-test".
#     Verify & Save. The key field then shows "•••••••• stored securely".
#
# Release builds log nothing, so app logcat greps can't prove these. The evidence used here is:
#   - the crash buffer (`logcat -b crash`) for migration or Keystore crashes,
#   - `dumpsys dbinfo` for the opened database and the statements Room ran,
#   - the mock provider's request log (mock_provider.log.jsonl) for submits, polls and cancels,
#   - the Settings screen: "stored securely" means the key decrypted; the
#     "Secure storage was reset" banner means it was wiped.
set -euo pipefail

PKG="${PKG:-com.visionstudio.ai}"
# The Kotlin namespace differs from the applicationId, so ".MainActivity" would not resolve.
ACTIVITY="$PKG/com.visionstudio.app.MainActivity"
V2_APK="${V2_APK:-visionstudio-v2-db2-release.apk}"
V3_APK="${V3_APK:-visionstudio-v3-db3-release.apk}"
MOCK_LOG="${MOCK_LOG:-mock_provider.log.jsonl}"

pause() { read -r -p "  >> $1 [Enter] " _; }
launch() { adb shell am start -W -n "$ACTIVITY" >/dev/null; }
crashes() {
  echo "-- crash buffer (empty is a pass):"
  adb logcat -d -b crash | grep -iE "FATAL|Exception" || echo "   (none)"
}
mock_since() {
  # Requests the mock received after line $1 of its log.
  [[ -f "$MOCK_LOG" ]] || { echo "   mock log $MOCK_LOG not found; is mock_provider.py running here?"; return; }
  tail -n +"$(( $1 + 1 ))" "$MOCK_LOG"
}
mock_lines() { [[ -f "$MOCK_LOG" ]] && wc -l < "$MOCK_LOG" || echo 0; }
jobs_for_pkg() { adb shell dumpsys jobscheduler | grep -E "JOB #.*$PKG/" || true; }

upgrade() {
  echo "== 1. Room v2 -> v3 in-place upgrade"
  adb uninstall "$PKG" >/dev/null 2>&1 || true
  adb install "$V2_APK"
  launch
  pause "Configure Settings as in this script's header, then generate one image so the Vault has a row"
  adb logcat -c; adb logcat -b crash -c
  adb install -r "$V3_APK"           # -r keeps /data/data; both APKs share one signing key
  adb shell am force-stop "$PKG"
  launch
  pause "Open the Vault tab once (that opens the database)"
  crashes
  echo "-- databases the process has open, and what Room ran on them:"
  adb shell dumpsys dbinfo "$PKG" | grep -E "visionstudio.db|user_version|index_generation_history_created_at_id|index_generation_history_media_type_created_at_id" \
    || echo "   (not in the recent-operations window; the Vault showing your row is the stronger check)"
  echo "PASS if: no crash, the Vault still shows your image, and Settings shows the key as stored securely."
  echo "  'PRAGMA user_version = 3' and the two *_id indexes above come from the 2->3 migration."
}

locked() {
  echo "== 2. Cold start behind the lock screen"
  echo "  Note: the master key is not tied to user authentication (no setUserAuthenticationRequired),"
  echo "  so after the first unlock since boot Keystore works while locked and nothing should fail."
  echo "  This guards against a regression; the wipe veto itself is covered by unit tests."
  adb shell input keyevent KEYCODE_SLEEP
  sleep 2
  adb shell dumpsys window | grep -E "mDreamingLockscreen|isStatusBarKeyguard|mShowingLockscreen" || true
  adb logcat -b crash -c
  adb shell am force-stop "$PKG"
  adb shell am start -n "$ACTIVITY" >/dev/null   # the process starts behind the keyguard
  sleep 5
  crashes
  adb shell input keyevent KEYCODE_WAKEUP
  pause "Unlock the device yourself, open Settings"
  echo "PASS if: Settings shows the key as stored securely and there is no 'Secure storage was reset' banner."
}

preempt() {
  echo "== 3. OS stop must not cancel the render at the provider"
  pause "Start a video in the Motion tab and wait until it shows a percentage"
  echo "-- WorkManager job for $PKG:"; jobs_for_pkg
  local mark; mark=$(mock_lines)
  read -r -p "  >> Stop it by (a) network loss or (b) jobscheduler timeout? [a/b] " how
  if [[ "$how" == "b" ]]; then
    read -r -p "  >> Job id (the number after the slash in 'JOB #u0aNN/ID' above): " id
    adb shell cmd jobscheduler timeout "$PKG" "$id"
  else
    adb shell svc wifi disable; adb shell svc data disable
  fi
  sleep 8
  echo "-- requests to the mock since the stop (there must be no '/cancel'):"
  mock_since "$mark"
  if mock_since "$mark" | grep -q '/cancel'; then echo "FAIL: a cancel was sent for an OS stop"; else echo "   no cancel sent"; fi
  if [[ "$how" != "b" ]]; then adb shell svc wifi enable; adb shell svc data enable; fi
  pause "Wait for the job card to update again once the network is back (WorkManager decides when)"
  echo "-- requests since the stop:"
  mock_since "$mark"
  echo "PASS if: no '/cancel', no second 'SUBMIT video', and polls continue for the same job id."
  echo "  The job stays POLLING in the app; WorkManager re-runs the worker, which resumes from the saved handle."
}

cancel() {
  echo "== 4. User Cancel reaches the provider"
  local mark; mark=$(mock_lines)
  pause "Tap Cancel on the running video job"
  sleep 3
  echo "-- requests since the tap (expect 'POST /v1/jobs/<id>/cancel'; Fal would be a PUT to cancel_url):"
  mock_since "$mark"
  if mock_since "$mark" | grep -q '/cancel'; then echo "   cancel sent"; else echo "FAIL: no cancel request reached the mock"; fi
  echo "-- WorkManager jobs left for $PKG (empty is a pass):"
  local left; left=$(jobs_for_pkg)
  [[ -z "$left" ]] && echo "   (none)" || echo "$left"
  echo "PASS if: one cancel request for that job id, no polls for it afterwards, no jobs left."
}

case "${1:-}" in
  upgrade) upgrade ;;
  locked) locked ;;
  preempt) preempt ;;
  cancel) cancel ;;
  *) sed -n '2,7p' "$0"; exit 64 ;;
esac
