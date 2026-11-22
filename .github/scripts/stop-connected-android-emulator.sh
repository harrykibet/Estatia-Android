#!/usr/bin/env bash
set -euo pipefail

API_LEVEL="${ANDROID_EMULATOR_API_LEVEL:-31}"
AVD_NAME="${ANDROID_EMULATOR_AVD_NAME:-estatia-pixel6-api31}"
EMULATOR_PORT="${ANDROID_EMULATOR_PORT:-5554}"
SERIAL="emulator-$EMULATOR_PORT"
STATE_DIR="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
PID_FILE="$STATE_DIR/$AVD_NAME.pid"

ADB="${ANDROID_HOME:-}/platform-tools/adb"

if [[ -x "$ADB" ]]; then
  "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
fi

if [[ -f "$PID_FILE" ]]; then
  EMULATOR_PID="$(cat "$PID_FILE")"
  for _ in $(seq 1 15); do
    if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
      break
    fi
    sleep 1
  done

  if kill -0 "$EMULATOR_PID" 2>/dev/null; then
    kill "$EMULATOR_PID" 2>/dev/null || true
  fi

  rm -f "$PID_FILE"
fi

echo "Stopped connected Android emulator $AVD_NAME (API $API_LEVEL)."
