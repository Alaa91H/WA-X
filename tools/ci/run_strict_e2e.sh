#!/usr/bin/env bash
# Run the instrumented suite on a hardware-accelerated emulator and leave the result and the
# diagnostics behind in build/strict-quality.
#
# The previous version of this script spent its whole budget re-downloading the system image
# and its AVD on every run and was killed at the job timeout before the first test executed.
# Nothing about a GitHub-hosted runner lets the SDK be cached in place, so the image is
# fetched once into a directory that is cached, and the AVD is cached with it.
#
# Exit codes:
#   0  the instrumented suite ran to completion
#   1  it ran and failed, or the emulator could not be brought up
#   2  the runner cannot host an accelerated emulator, so nothing was attempted
set -u

API_LEVEL="${STRICT_E2E_API:-35}"
AVD_NAME="wax-strict-api${API_LEVEL}"
IMAGE="system-images;android-${API_LEVEL};google_apis;x86_64"
LOG_DIR="build/strict-quality"
CACHE_ROOT="${STRICT_E2E_CACHE:-$HOME/.cache/wax-e2e}"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
SDKMANAGER="${SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="${SDK_ROOT}/cmdline-tools/latest/bin/avdmanager"
EMULATOR="${SDK_ROOT}/emulator/emulator"
ADB="${SDK_ROOT}/platform-tools/adb"

# A cold boot on a hosted runner is slow but not unbounded. Past this the emulator is not
# going to appear, and the emulator log is more useful than another ten minutes of waiting.
BOOT_ATTEMPTS="${STRICT_E2E_BOOT_ATTEMPTS:-300}"
BOOT_INTERVAL="${STRICT_E2E_BOOT_INTERVAL:-4}"

mkdir -p "$LOG_DIR" "$CACHE_ROOT"

cleanup() {
  "$ADB" -s emulator-5554 emu kill >/dev/null 2>&1 || true
}
trap cleanup EXIT

# A hosted runner without nested virtualization is an infrastructure fact, not a defect in
# this repository, so it is reported as its own outcome instead of as a failed gate.
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

image_zip="${CACHE_ROOT}/$(echo "$IMAGE" | tr ';' '_').zip"
image_dir="${CACHE_ROOT}/$(echo "$IMAGE" | tr ';' '_')"

# Unpack once into the SDK. The SDK root is not cacheable, so the zip is what survives
# between runs; sdkmanager then sees the package already installed and returns immediately
# instead of downloading gigabytes.
if [[ -f "$image_zip" ]]; then
  echo "Restoring the cached system image from $image_zip"
  mkdir -p "$image_dir"
  unzip -q -o "$image_zip" -d "$image_dir"
fi

yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" "platform-tools" "emulator" "$IMAGE" || true

if [[ ! -d "${SDK_ROOT}/${IMAGE//;//}" && -d "$image_dir" ]]; then
  # sdkmanager failed but the cached image is present: install it from the unpacked copy.
  cp -R "${image_dir}/${IMAGE//;//}" "${SDK_ROOT}/${IMAGE//;//}" 2>/dev/null || true
fi

if [[ ! -x "$EMULATOR" || ! -x "$ADB" ]]; then
  echo "::error::Android emulator/platform-tools installation is incomplete"
  exit 1
fi

# Save the image so the next run starts from it rather than from the network.
if [[ ! -f "$image_zip" && -d "${SDK_ROOT}/${IMAGE//;//}" ]]; then
  (cd "$SDK_ROOT/${IMAGE//;//}" && zip -q -r "$image_zip" .) || true
fi

echo "no" | "$AVDMANAGER" create avd --force --name "$AVD_NAME" --package "$IMAGE" --device "pixel_6"

"$EMULATOR" -avd "$AVD_NAME" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect \
  -no-snapshot -wipe-data >"$LOG_DIR/emulator-api${API_LEVEL}.log" 2>&1 &
emulator_pid=$!

"$ADB" wait-for-device
booted=""
for _ in $(seq 1 "$BOOT_ATTEMPTS"); do
  booted="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  [[ "$booted" == "1" ]] && break
  sleep "$BOOT_INTERVAL"
done
if [[ "$booted" != "1" ]]; then
  echo "::error::Android emulator did not finish booting after $((BOOT_ATTEMPTS * BOOT_INTERVAL))s"
  kill "$emulator_pid" >/dev/null 2>&1 || true
  exit 1
fi

"$ADB" shell settings put global window_animation_scale 0
"$ADB" shell settings put global transition_animation_scale 0
"$ADB" shell settings put global animator_duration_scale 0
"$ADB" shell input keyevent 82 || true

set +e
./gradlew --no-daemon --stacktrace --continue :app:createDebugAndroidTestCoverageReport
rc=$?
set -e

"$ADB" logcat -d >"$LOG_DIR/logcat-api${API_LEVEL}.txt" || true
exit "$rc"