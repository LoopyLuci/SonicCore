#!/usr/bin/env bash
# SonicCore full verification in ONE pass.
#
# Compiles every module, runs all unit tests, and builds + verifies the debug APK.
# Use this instead of per-module compile checks: one invocation, one result, no
# storm of intermediate pass/fail notifications.
set -uo pipefail

cd "$(dirname "$0")"

export JAVA_HOME="C:/Users/Server/soniccore-toolchain/jdk-21.0.12+8"
export ANDROID_HOME="C:/Users/Server/soniccore-toolchain/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
APK="app/build/outputs/apk/full/debug/app-full-debug.apk"
FOSS_APK="app/build/outputs/apk/foss/debug/app-foss-debug.apk"
TMPDIR_DEX="${TMPDIR:-${LOCALAPPDATA:-/tmp}}/soniccore-foss-classes.dex"

echo "=============================================="
echo " SonicCore verification"
echo "=============================================="

# assembleDebug transitively compiles every module the app depends on,
# so this single task covers all 17 modules. Add the release bundle and the
# instrumented test APK so signing and androidTest wiring are verified too.
# assembleDebug transitively compiles every module the app depends on, so this covers
# all modules. Both FLAVORS are built: `full` (Cast SDK) and `foss` (no proprietary
# deps, F-Droid). A change that only compiles with Play Services present would
# otherwise break the F-Droid build silently.
./gradlew :app:assembleFullDebug :app:assembleFossDebug \
  :app:assembleFullDebugAndroidTest :app:bundleFullRelease test \
  --no-daemon "$@"
STATUS=$?

echo
echo "---------- results ----------"

if [ $STATUS -ne 0 ]; then
  echo "BUILD: FAILED (gradle exit $STATUS)"
  exit $STATUS
fi
echo "BUILD: ok"

# Aggregate unit-test totals across every module.
python - <<'PY'
import glob, re
total = failed = 0
for f in glob.glob("**/test-results/**/*.xml", recursive=True):
    head = open(f, encoding="utf-8", errors="ignore").read(2000)
    m = re.search(r'tests="(\d+)".*?failures="(\d+)".*?errors="(\d+)"', head)
    if m:
        total += int(m.group(1))
        failed += int(m.group(2)) + int(m.group(3))
print(f"TESTS: {total} run, {failed} failed")
PY

if [ ! -f "$APK" ]; then
  echo "APK:   MISSING — build reported success but produced no artifact"
  exit 1
fi

SIZE=$(stat -c %s "$APK" 2>/dev/null || stat -f %z "$APK")
echo "APK:   $APK ($((SIZE / 1024 / 1024)) MB)"

# Trust the artifact, not the log.
"$BUILD_TOOLS/aapt2.exe" dump badging "$APK" 2>/dev/null \
  | grep -E "^package:|^launchable-activity:" \
  | sed 's/^/       /'

"$BUILD_TOOLS/apksigner.bat" verify "$APK" >/dev/null 2>&1 \
  && echo "SIGN:  verified" \
  || echo "SIGN:  UNVERIFIED"

COMPONENTS=$("$BUILD_TOOLS/aapt2.exe" dump xmltree --file AndroidManifest.xml "$APK" 2>/dev/null \
  | grep -oE '"com\.soniccore\.[A-Za-z.]+"' | sort -u | wc -l)
echo "MANIFEST: $COMPONENTS com.soniccore components registered"

# ---------- FOSS flavor purity (F-Droid requirement) ----------
if [ -f "$FOSS_APK" ]; then
  FOSS_SIZE=$(stat -c %s "$FOSS_APK" 2>/dev/null || stat -f %z "$FOSS_APK")
  echo "FOSS:  $FOSS_APK ($((FOSS_SIZE / 1024 / 1024)) MB)"
  # The whole point of the flavor: no proprietary Google Play Services. A transitive
  # dependency could reintroduce it silently, so check the compiled dex, not the config.
  unzip -p "$FOSS_APK" classes.dex > "$TMPDIR_DEX" 2>/dev/null
  # grep -c on a binary exits 1 when there are no matches, so `|| echo 0` appended a
  # SECOND value and produced "0\n0". Count with tr instead: no exit-code dependency.
  GMS=$(tr -c '[:print:]' '\n' < "$TMPDIR_DEX" 2>/dev/null | grep -c 'com/google/android/gms' || true)
  GMS=${GMS:-0}
  if [ "$GMS" -eq 0 ] 2>/dev/null; then
    echo "FOSS:  no Google Play Services references — F-Droid compliant"
  else
    echo "FOSS:  FAIL — $GMS com.google.android.gms references leaked in"
    STATUS=1
  fi
  rm -f "$TMPDIR_DEX"
else
  echo "FOSS:  APK missing"
fi

# ---------- release bundle ----------
AAB="app/build/outputs/bundle/fullRelease/app-full-release.aab"
if [ -f "$AAB" ]; then
  AAB_SIZE=$(stat -c %s "$AAB" 2>/dev/null || stat -f %z "$AAB")
  echo "AAB:   $AAB ($((AAB_SIZE / 1024 / 1024)) MB, R8 minified)"
  # An AAB is a zip; confirm the expected module layout and signing block.
  if unzip -l "$AAB" >/dev/null 2>&1; then
    ENTRIES=$(unzip -l "$AAB" | grep -cE "base/(dex|manifest|res|assets)")
    echo "AAB:   $ENTRIES base-module entries"
    unzip -l "$AAB" | grep -qE "META-INF/.*\.(RSA|SF)" \
      && echo "AAB:   signature block present" \
      || echo "AAB:   NOT signed"
  fi
else
  echo "AAB:   not built (run with :app:bundleRelease)"
fi

# ---------- instrumented test APK ----------
TEST_APK="app/build/outputs/apk/androidTest/full/debug/app-full-debug-androidTest.apk"
if [ -f "$TEST_APK" ]; then
  echo "TEST APK: present — run ./gradlew :app:connectedFullDebugAndroidTest (device must be UNLOCKED: bash check-device.sh)"
else
  echo "TEST APK: not built"
fi

echo "------------------------------"
