#!/usr/bin/env bash
# Compile the shipped Wear OS Kotlin SDK (watch-sdk/wearos).
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
wrapper="$repo_root/android/gradlew"
if [[ ! -x "$wrapper" ]]; then
  echo "Expected Gradle wrapper at $wrapper"
  exit 1
fi

"$wrapper" -p "$repo_root/watch-sdk/wearos" assembleDebug --no-daemon
echo "OK: watch-sdk/wearos assembleDebug succeeded"
