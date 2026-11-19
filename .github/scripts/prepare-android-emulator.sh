#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${ANDROID_HOME:-}" || ! -d "$ANDROID_HOME" ]]; then
  echo "::error::ANDROID_HOME is missing or does not point to an Android SDK directory."
  exit 1
fi

# GitHub-hosted Ubuntu runners expose KVM, but the device permissions must be prepared.
echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
sudo udevadm control --reload-rules
sudo udevadm trigger --name-match=kvm
if [[ ! -c /dev/kvm ]]; then
  echo "::error::GitHub runner does not expose the KVM device required by x86_64 Android emulators."
  exit 1
fi
sudo chmod 0666 /dev/kvm
ls -l /dev/kvm

# Install the emulator package once per runner, before Gradle Managed Device setup.
SDKMANAGER="$(command -v sdkmanager || true)"
if [[ -z "$SDKMANAGER" ]]; then
  SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
fi
if [[ ! -x "$SDKMANAGER" ]]; then
  echo "::error::Android SDK manager was not found under ANDROID_HOME."
  exit 1
fi
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" "emulator"

EMULATOR="$ANDROID_HOME/emulator/emulator"
if [[ ! -x "$EMULATOR" ]]; then
  echo "::error::Android Emulator binary was not found after SDK package installation."
  exit 1
fi
"$EMULATOR" -accel-check
