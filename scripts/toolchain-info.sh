#!/bin/sh
set -eu

printf '%s\n' \
  'O.R.I.O.N. toolchain profile: docs/toolchain/ToolchainProfile.md' \
  "Gradle: $1" \
  "Java: $(java -version 2>&1 | sed -n '1p')" \
  "Android SDK: ${ANDROID_SDK_ROOT:-not configured}"
