#!/usr/bin/env bash
# Verify a connected device can actually run Compose UI tests.
#
# Compose instrumented tests need a VISIBLE, UNLOCKED window. On a dozing or locked
# device the Activity launches and is immediately paused by the keyguard, and every
# test fails with:
#   IllegalStateException: No compose hierarchies found in the app
# which looks like an app bug and sends you bisecting source that is fine.
#
# Exit 0 = ready, 1 = not ready (reason printed).
set -uo pipefail

ADB="${ANDROID_HOME:-C:/Users/Server/soniccore-toolchain/sdk}/platform-tools/adb.exe"
SERIAL="${1:-}"
if [ -n "$SERIAL" ]; then ADB_CMD=("$ADB" -s "$SERIAL"); else ADB_CMD=("$ADB"); fi

fail() { echo "NOT READY: $1"; exit 1; }

DEVICES=$("$ADB" devices | grep -cw "device$")
[ "$DEVICES" -eq 0 ] && fail "no device attached"
[ "$DEVICES" -gt 1 ] && echo "WARNING: $DEVICES devices attached — AGP shards tests and an empty shard fails the build"

BOOT=$("${ADB_CMD[@]}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
[ "$BOOT" != "1" ] && fail "device has not finished booting"

WAKE=$("${ADB_CMD[@]}" shell dumpsys power 2>/dev/null | grep -oE "mWakefulness=[A-Za-z]+" | head -1 | cut -d= -f2 | tr -d '\r')
if [ "$WAKE" != "Awake" ]; then
  echo "screen is '$WAKE' — attempting to wake"
  "${ADB_CMD[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
  sleep 2
  WAKE=$("${ADB_CMD[@]}" shell dumpsys power 2>/dev/null | grep -oE "mWakefulness=[A-Za-z]+" | head -1 | cut -d= -f2 | tr -d '\r')
fi
[ "$WAKE" != "Awake" ] && fail "screen will not wake (mWakefulness=$WAKE)"

KEYGUARD=$("${ADB_CMD[@]}" shell dumpsys window 2>/dev/null | grep -oE "isKeyguardShowing=(true|false)" | head -1 | cut -d= -f2 | tr -d '\r')
if [ "$KEYGUARD" = "true" ]; then
  "${ADB_CMD[@]}" shell input keyevent KEYCODE_MENU >/dev/null 2>&1
  sleep 1
  KEYGUARD=$("${ADB_CMD[@]}" shell dumpsys window 2>/dev/null | grep -oE "isKeyguardShowing=(true|false)" | head -1 | cut -d= -f2 | tr -d '\r')
fi
if [ "$KEYGUARD" = "true" ]; then
  cat <<'MSG'
NOT READY: the lockscreen is showing.

Compose UI tests cannot run behind a keyguard. A PIN/pattern lock cannot be cleared
over adb — unlock the device by hand, then keep it awake:

    adb shell svc power stayon true

Component/service/receiver tests (ComponentResolutionTest) do NOT need the screen and
will still pass; only Compose UI assertions are affected.
MSG
  exit 1
fi

# Keep it awake for the duration of the run.
"${ADB_CMD[@]}" shell svc power stayon true >/dev/null 2>&1

echo "READY: device awake and unlocked (boot_completed=1, wakefulness=$WAKE, keyguard=$KEYGUARD)"
