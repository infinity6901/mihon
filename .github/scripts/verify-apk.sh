#!/usr/bin/env bash
set -euo pipefail

APK="${1:?Usage: verify-apk.sh <path-to-apk>}"
test -s "$APK"

BUILD_TOOLS_DIR="$(find "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n1)"
AAPT2="$BUILD_TOOLS_DIR/aapt2"
APKSIGNER="$BUILD_TOOLS_DIR/apksigner"

echo "APK: $APK ($(du -h "$APK" | cut -f1))"

if [ -x "$AAPT2" ]; then
  echo "--- aapt2 dump badging (summary) ---"
  "$AAPT2" dump badging "$APK" | grep -E '^(package:|sdkVersion:|targetSdkVersion:|native-code:|uses-permission:.*NFC)' || true
fi

if [ -x "$APKSIGNER" ]; then
  echo "--- apksigner verify ---"
  "$APKSIGNER" verify --print-certs "$APK"
else
  echo "apksigner not found in $BUILD_TOOLS_DIR" >&2
  exit 1
fi
