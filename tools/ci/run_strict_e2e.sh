#!/usr/bin/env bash
# Deterministic, fail-bounded Android instrumentation runner for GitHub-hosted CI.
#
# Every external phase has its own timeout. A stalled SDK download, adb connection,
# emulator boot, instrumentation process or cleanup therefore has a finite upper bound
# and cannot hold a GitHub Actions runner indefinitely.
#
# Exit codes:
#   0  the instrumented suite ran to completion
#   1  setup, emulator boot, instrumentation or cleanup-relevant validation failed
#   2  the runner cannot host an accelerated emulator, so nothing was attempted
set -Eeuo pipefail

API_LEVEL="${STRICT_E2E_API:-35}"
AVD_NAME="wax-strict-api${API_LEVEL}"
IMAGE="system-images;android-${API_LEVEL};google_apis;x86_64"
LOG_DIR="build/strict-quality/e2e"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
SDKMANAGER="${SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="${SDK_ROOT}/cmdline-tools/latest/bin/avdmanager"
EMULATOR="${SDK_ROOT}/emulator/emulator"
ADB="${SDK_ROOT}/platform-tools/adb"
SDK_IMAGE_DIR="${SDK_ROOT}/system-images/android-${API_LEVEL}/google_apis/x86_64"
ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
export ANDROID_AVD_HOME

SDK_TOOLS_TIMEOUT="${STRICT_E2E_SDK_TOOLS_TIMEOUT:-90}"
SDK_IMAGE_TIMEOUT="${STRICT_E2E_SDK_IMAGE_TIMEOUT:-300}"
AVD_CREATE_TIMEOUT="${STRICT_E2E_AVD_CREATE_TIMEOUT:-45}"
ADB_WAIT_TIMEOUT="${STRICT_E2E_ADB_WAIT_TIMEOUT:-60}"
BOOT_TIMEOUT="${STRICT_E2E_BOOT_TIMEOUT:-180}"
TEST_TIMEOUT="${STRICT_E2E_TEST_TIMEOUT:-480}"
CLEANUP_TIMEOUT="${STRICT_E2E_CLEANUP_TIMEOUT:-15}"

mkdir -p "$LOG_DIR" "$ANDROID_AVD_HOME"

emulator_pid=""
started_at="$(date +%s)"

phase() {
  printf '\n==> %s\n' "$1"
}

run_with_timeout() {
  local label="$1"
  local seconds="$2"
  shift 2

  phase "$label (timeout: ${seconds}s)"
  if timeout --foreground --signal=TERM --kill-after=20s "${seconds}s" "$@"; then
    return 0
  fi

  local rc=$?
  if [[ "$rc" -eq 124 || "$rc" -eq 137 ]]; then
    echo "::error::${label} exceeded its ${seconds}s timeout"
  else
    echo "::error::${label} failed with exit code ${rc}"
  fi
  return "$rc"
}

dump_emulator_log() {
  if [[ -f "$LOG_DIR/emulator-api${API_LEVEL}.log" ]]; then
    echo "::group::Emulator log tail"
    tail -n 200 "$LOG_DIR/emulator-api${API_LEVEL}.log" || true
    echo "::endgroup::"
  fi
}

cleanup() {
  local rc=$?
  trap - EXIT INT TERM
  set +e

  phase "Cleanup"
  if [[ -x "$ADB" ]]; then
    timeout "${CLEANUP_TIMEOUT}s" "$ADB" -s emulator-5554 logcat -d       >"$LOG_DIR/logcat-api${API_LEVEL}.txt" 2>&1 || true
    timeout "${CLEANUP_TIMEOUT}s" "$ADB" -s emulator-5554 emu kill >/dev/null 2>&1 || true
  fi

  if [[ -n "$emulator_pid" ]] && kill -0 "$emulator_pid" >/dev/null 2>&1; then
    kill -TERM "$emulator_pid" >/dev/null 2>&1 || true
    for _ in $(seq 1 10); do
      kill -0 "$emulator_pid" >/dev/null 2>&1 || break
      sleep 1
    done
    kill -KILL "$emulator_pid" >/dev/null 2>&1 || true
  fi

  if [[ -x ./gradlew ]]; then
    timeout "${CLEANUP_TIMEOUT}s" ./gradlew --stop >/dev/null 2>&1 || true
  fi

  local elapsed=$(( $(date +%s) - started_at ))
  echo "E2E wall time: ${elapsed}s"
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if ! command -v timeout >/dev/null 2>&1; then
  echo "::error::GNU timeout is required for bounded E2E execution"
  exit 1
fi

if [[ ! -e /dev/kvm ]]; then
  echo "::warning::/dev/kvm is unavailable, so no accelerated emulator can run here."
  echo "E2E was not attempted. The gate is binding on a runner that can host an emulator."
  exit 2
fi
sudo chmod 666 /dev/kvm

if [[ ! -x "$SDKMANAGER" || ! -x "$AVDMANAGER" ]]; then
  echo "::error::Android cmdline-tools were not found under $SDK_ROOT"
  exit 1
fi

# Accept licenses with a hard bound. A license prompt must never own the runner forever.
timeout 60s bash -c 'yes | "$1" --licenses >/dev/null 2>&1' _ "$SDKMANAGER" || true

# The hosted image normally carries platform-tools/emulator. Install only when absent,
# and fail closed if that installation cannot complete within the bounded window.
if [[ ! -x "$EMULATOR" || ! -x "$ADB" ]]; then
  run_with_timeout "Install Android emulator host tools" "$SDK_TOOLS_TIMEOUT"     "$SDKMANAGER" "platform-tools" "emulator"
fi

if [[ ! -x "$EMULATOR" || ! -x "$ADB" ]]; then
  echo "::error::Android emulator/platform-tools installation is incomplete"
  exit 1
fi

# Install the system image only when the hosted runner does not already provide it.
# The previous hand-rolled zip cache was slower than the observed clean download and
# could not be saved after a failed test, so it added complexity without reducing wall time.
if [[ ! -f "$SDK_IMAGE_DIR/source.properties" ]]; then
  run_with_timeout "Install Android API ${API_LEVEL} system image" "$SDK_IMAGE_TIMEOUT" \
    "$SDKMANAGER" "$IMAGE"
fi

if [[ ! -f "$SDK_IMAGE_DIR/source.properties" ]]; then
  echo "::error::Android system image installation did not produce $SDK_IMAGE_DIR/source.properties"
  exit 1
fi

rm -rf "$ANDROID_AVD_HOME/${AVD_NAME}.avd" "$ANDROID_AVD_HOME/${AVD_NAME}.ini"
run_with_timeout "Create clean AVD" "$AVD_CREATE_TIMEOUT"   bash -c 'echo no | "$1" create avd --force --name "$2" --package "$3" --device pixel_6'   _ "$AVDMANAGER" "$AVD_NAME" "$IMAGE"

phase "Verify AVD discovery"
if ! timeout 15s "$EMULATOR" -list-avds | grep -Fxq "$AVD_NAME"; then
  echo "::error::Created AVD $AVD_NAME is not discoverable in ANDROID_AVD_HOME=$ANDROID_AVD_HOME"
  find "$ANDROID_AVD_HOME" -maxdepth 2 -type f -print || true
  exit 1
fi

phase "Start hardware-accelerated emulator"
"$EMULATOR"   -avd "$AVD_NAME"   -no-window   -no-audio   -no-boot-anim   -no-snapshot   -wipe-data   -accel on   -cores 2   -memory 2048   -gpu swiftshader_indirect   >"$LOG_DIR/emulator-api${API_LEVEL}.log" 2>&1 &
emulator_pid=$!

if ! timeout --foreground --signal=TERM --kill-after=10s "${ADB_WAIT_TIMEOUT}s" "$ADB" wait-for-device; then
  echo "::error::adb did not see the emulator within ${ADB_WAIT_TIMEOUT}s"
  dump_emulator_log
  exit 1
fi

phase "Wait for Android boot (timeout: ${BOOT_TIMEOUT}s)"
boot_deadline=$((SECONDS + BOOT_TIMEOUT))
booted=""
while (( SECONDS < boot_deadline )); do
  if ! kill -0 "$emulator_pid" >/dev/null 2>&1; then
    echo "::error::The emulator exited before Android finished booting"
    dump_emulator_log
    exit 1
  fi

  booted="$(timeout 5s "$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  if [[ "$booted" == "1" ]]; then
    break
  fi
  sleep 2
done

if [[ "$booted" != "1" ]]; then
  echo "::error::Android emulator did not finish booting within ${BOOT_TIMEOUT}s"
  dump_emulator_log
  exit 1
fi

timeout 10s "$ADB" shell settings put global window_animation_scale 0 || true
timeout 10s "$ADB" shell settings put global transition_animation_scale 0 || true
timeout 10s "$ADB" shell settings put global animator_duration_scale 0 || true
timeout 10s "$ADB" shell input keyevent 82 || true

phase "Run instrumented tests with a hard ${TEST_TIMEOUT}s ceiling"
if timeout --foreground --signal=TERM --kill-after=30s "${TEST_TIMEOUT}s"     ./gradlew --no-daemon --stacktrace --continue :app:createDebugAndroidTestCoverageReport; then
  test_rc=0
else
  test_rc=$?
  if [[ "$test_rc" -eq 124 || "$test_rc" -eq 137 ]]; then
    echo "::error::Android instrumentation exceeded its ${TEST_TIMEOUT}s timeout"
  else
    echo "::error::Android instrumentation failed with exit code ${test_rc}"
  fi
fi

exit "$test_rc"
