#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${ANDROID_HOME:-}" || ! -d "$ANDROID_HOME" ]]; then
  echo "::error::ANDROID_HOME is missing or does not point to an Android SDK directory."
  exit 1
fi

SDKMANAGER="$(command -v sdkmanager || true)"
if [[ -z "$SDKMANAGER" ]]; then
  SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
fi
AVDMANAGER="$(command -v avdmanager || true)"
if [[ -z "$AVDMANAGER" ]]; then
  AVDMANAGER="$(dirname "$SDKMANAGER")/avdmanager"
fi
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"

for tool in "$SDKMANAGER" "$AVDMANAGER" "$ADB" "$EMULATOR"; do
  if [[ ! -x "$tool" ]]; then
    echo "::error::Required Android SDK tool is missing or not executable: $tool"
    exit 1
  fi
done

API_LEVEL="${ANDROID_EMULATOR_API_LEVEL:-31}"
AVD_NAME="${ANDROID_EMULATOR_AVD_NAME:-estatia-pixel6-api31}"
AVD_DEVICE="${ANDROID_EMULATOR_DEVICE:-pixel_6}"
EMULATOR_PORT="${ANDROID_EMULATOR_PORT:-5554}"
SYSTEM_IMAGE="system-images;android-$API_LEVEL;google_apis;x86_64"
SERIAL="emulator-$EMULATOR_PORT"
STATE_DIR="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
AVD_LOG="$STATE_DIR/$AVD_NAME.log"
PID_FILE="$STATE_DIR/$AVD_NAME.pid"

mkdir -p "$STATE_DIR" "${ANDROID_AVD_HOME:-$HOME/.android/avd}"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"

# Accept SDK package licenses before installing the configured Google APIs image.
set +o pipefail
yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null
set -o pipefail

"$SDKMANAGER" --sdk_root="$ANDROID_HOME" --install \
  "platform-tools" \
  "$SYSTEM_IMAGE"

if [[ ! -d "$ANDROID_AVD_HOME/$AVD_NAME.avd" ]]; then
  printf 'no\n' | "$AVDMANAGER" --verbose create avd \
    --force \
    --name "$AVD_NAME" \
    --package "$SYSTEM_IMAGE" \
    --device "$AVD_DEVICE"
fi

"$ADB" start-server

# Do not inherit a stale emulator process if this script is rerun on the same runner.
if "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
  "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
  sleep 2
fi

nohup "$EMULATOR" \
  -avd "$AVD_NAME" \
  -port "$EMULATOR_PORT" \
  -no-window \
  -no-audio \
  -no-boot-anim \
  -no-snapshot \
  -wipe-data \
  -gpu swiftshader_indirect \
  -accel on \
  -cores 2 \
  -memory 2048 \
  >"$AVD_LOG" 2>&1 &
EMULATOR_PID=$!
printf '%s\n' "$EMULATOR_PID" > "$PID_FILE"

echo "Started $AVD_NAME (PID $EMULATOR_PID, serial $SERIAL)."
echo "Emulator log: $AVD_LOG"

# Wait for both the ADB transport and Android framework boot completion.
BOOTED=false
for attempt in $(seq 1 90); do
  if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
    echo "::error::Android Emulator exited before boot completed."
    tail -n 120 "$AVD_LOG" || true
    exit 1
  fi

  DEVICE_STATE="$("$ADB" -s "$SERIAL" get-state 2>/dev/null || true)"
  BOOT_COMPLETED="$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"

  if [[ "$DEVICE_STATE" == "device" && "$BOOT_COMPLETED" == "1" ]]; then
    BOOTED=true
    break
  fi

  sleep 5
done

if [[ "$BOOTED" != "true" ]]; then
  echo "::error::Android Emulator did not reach sys.boot_completed=1 within 450 seconds."
  "$ADB" devices -l || true
  tail -n 160 "$AVD_LOG" || true
  exit 1
fi

if [[ -n "${GITHUB_ENV:-}" ]]; then
  printf 'ANDROID_SERIAL=%s\n' "$SERIAL" >> "$GITHUB_ENV"
fi

echo "Connected Android emulator is ready:"
"$ADB" devices -l
echo "Android API level: $("$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
