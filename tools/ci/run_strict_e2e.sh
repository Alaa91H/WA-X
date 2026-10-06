#!/usr/bin/env bash
set -u

API_LEVEL="${STRICT_E2E_API:-35}"
AVD_NAME="wax-strict-api${API_LEVEL}"
IMAGE="system-images;android-${API_LEVEL};google_apis;x86_64"
LOG_DIR="build/strict-quality"
mkdir -p "$LOG_DIR"

cleanup() {
  adb -s emulator-5554 emu kill >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [[ ! -e /dev/kvm ]]; then
  echo "::error::/dev/kvm is unavailable; hardware-accelerated E2E is required"
  exit 1
fi
sudo chmod 666 /dev/kvm

yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager "platform-tools" "emulator" "$IMAGE"

echo "no" | avdmanager create avd   --force   --name "$AVD_NAME"   --package "$IMAGE"   --device "pixel_6"

emulator   -avd "$AVD_NAME"   -no-window   -no-audio   -no-boot-anim   -gpu swiftshader_indirect   -no-snapshot   -wipe-data   >"$LOG_DIR/emulator-api${API_LEVEL}.log" 2>&1 &

adb wait-for-device
booted=""
for _ in $(seq 1 180); do
  booted="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  [[ "$booted" == "1" ]] && break
  sleep 2
done
if [[ "$booted" != "1" ]]; then
  echo "::error::Android emulator did not finish booting"
  exit 1
fi

adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell input keyevent 82 || true

set +e
./gradlew --no-daemon --stacktrace --continue :app:createDebugAndroidTestCoverageReport
rc=$?
set -e

adb logcat -d >"$LOG_DIR/logcat-api${API_LEVEL}.txt" || true
exit "$rc"
