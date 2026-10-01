#!/usr/bin/env bash
# Executed by android-emulator-runner: Compose/instrumented tests on the debug build, then an
# install/launch/rotate/upgrade smoke of the (throw-away-signed) minified release APK.
set -euo pipefail
./gradlew :app:connectedDebugAndroidTest --stacktrace
tools/release/device_smoke.sh "$SIGNED_APK" "$DEBUG_APK"
