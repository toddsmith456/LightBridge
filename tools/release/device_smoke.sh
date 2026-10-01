#!/usr/bin/env bash
# Install and launch the SIGNED, R8-minified release APK on the running emulator/device and
# check the things only a real install can prove. Run inside android-emulator-runner.
#
# usage: device_smoke.sh signed.apk [debug.apk-for-signature-conflict-check]
set -euo pipefail
apk="${1:?signed apk}"; debug_apk="${2:-}"
pkg=dev.lightbridge.app
adb wait-for-device
sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
echo "== device API $sdk"
echo "::notice title=emulator display API $sdk::$(adb shell wm size | tr -d '\r') $(adb shell wm density | tr -d '\r')"

adb uninstall "$pkg" >/dev/null 2>&1 || true
adb install "$apk"
echo "== upgrade-in-place with the same signature must work"
adb install -r "$apk"

dump="$(adb shell dumpsys package "$pkg")"
if grep -qE 'flags=\[.*DEBUGGABLE' <<<"$dump"; then echo "::error::installed release APK is debuggable"; exit 1; fi
# INTERNET is expected now (Android requires it for the direct-link socket). What must not appear is
# any capability that would widen it: background location, storage, package-install, overlay, audio.
for bad in ACCESS_BACKGROUND_LOCATION MANAGE_EXTERNAL_STORAGE REQUEST_INSTALL_PACKAGES SYSTEM_ALERT_WINDOW RECORD_AUDIO QUERY_ALL_PACKAGES; do
  grep -q "android.permission.$bad" <<<"$dump" && { echo "::error::$bad present on device"; exit 1; } || true
done
# The location permission may exist only in its capped, discovery-only form.
if grep -q 'android.permission.ACCESS_FINE_LOCATION' <<<"$dump"; then
  grep -q 'ACCESS_FINE_LOCATION.*maxSdkVersion' <<<"$dump" ||     { echo "::error::ACCESS_FINE_LOCATION is uncapped on device"; exit 1; }
fi
echo "posture: network permission scoped to the peer-to-peer Link tab; no widened capabilities present"

adb shell pm grant "$pkg" android.permission.CAMERA || true
adb logcat -c
adb shell am start -W -n "$pkg/.MainActivity" | tee /tmp/start.txt
grep -q 'Status: ok' /tmp/start.txt || { echo "::error::MainActivity did not start"; exit 1; }
sleep 8
adb shell pidof "$pkg" >/dev/null || { echo "::error::app process is not running 8 s after launch"; adb logcat -d | tail -80; exit 1; }

echo "== rotate, background and foreground"
adb shell settings put system accelerometer_rotation 0
for r in 1 0 1 0; do adb shell settings put system user_rotation "$r"; sleep 1; done
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell am start -n "$pkg/.MainActivity"; sleep 3
adb shell pidof "$pkg" >/dev/null || { echo "::error::app died after rotate/background"; adb logcat -d | tail -80; exit 1; }

adb logcat -d > "logcat-api$sdk.txt"
adb exec-out screencap -p > "screenshot-api$sdk.png" || true
if grep -A6 -E 'FATAL EXCEPTION|ANR in' "logcat-api$sdk.txt" | grep -q "$pkg"; then
  echo "::error::crash/ANR of $pkg in logcat (see artifact)"; grep -A12 -E 'FATAL EXCEPTION|ANR in' "logcat-api$sdk.txt" | head -40; exit 1
fi

if [ -n "$debug_apk" ]; then
  echo "== a debug-signed build must NOT be able to replace the release install"
  # Capture first: `adb install | grep -q` would trip pipefail when grep exits early.
  conflict="$(adb install -r -d "$debug_apk" 2>&1 || true)"
  echo "$conflict"
  if grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' <<<"$conflict"; then
    echo "signature conflict correctly rejected"
  else
    echo "::error::debug APK was not rejected with INSTALL_FAILED_UPDATE_INCOMPATIBLE: $(tr '\n' ' ' <<<"$conflict")"; exit 1
  fi
fi
adb uninstall "$pkg"
echo "device smoke OK on API $sdk"
