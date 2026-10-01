#!/usr/bin/env python3
"""Fail the build if the *final* APK manifest violates LightBridge's security posture.

Reads the merged, packaged manifest with the Android SDK's `apkanalyzer`, so it checks what
users actually install (after manifest merging and R8), not just the source manifest.

usage: manifest_gate.py path/to/app-release.apk
"""
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
PACKAGE = "dev.lightbridge.app"

# Permissions the packaged app may request.
#
# LightBridge used to ship with no network permission at all. The Link tab (direct, end-to-end
# encrypted Wi-Fi Direct transfer) forced one exception: Android requires INTERNET for *any* socket,
# including one that never leaves the two paired devices — that is documented Wi-Fi Direct behaviour,
# not a design choice. The rule is therefore no longer "no network permission" but something stricter
# and checkable: the network permission may exist, and there must be no way to reach anything except a
# literal address in a peer-to-peer range. That last part is enforced at runtime by
# dev.lightbridge.link.LinkAddressPolicy and covered by its unit tests (see :link).
ALLOWED_PERMISSIONS = {
    "android.permission.CAMERA",
    # The Link tab: a direct socket between two paired devices, plus the radio control it needs.
    "android.permission.INTERNET",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_WIFI_STATE",
    "android.permission.NEARBY_WIFI_DEVICES",
    # Peer discovery on Android 12 and below only. Both must stay capped, or they would grant location.
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    # Added automatically by AndroidX for non-exported dynamic receivers (signature-level, own package).
    f"{PACKAGE}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
}
# Never acceptable, whatever the reason. Each one buys reach or reachability the app does not need.
DENIED_PERMISSIONS = {
    "android.permission.ACCESS_BACKGROUND_LOCATION": "background location is never needed",
    "android.permission.RECORD_AUDIO": "the app has no microphone feature",
    "android.permission.READ_EXTERNAL_STORAGE": "files are read through the system picker, never the shared store",
    "android.permission.WRITE_EXTERNAL_STORAGE": "the app writes only to its own private inbox",
    "android.permission.MANAGE_EXTERNAL_STORAGE": "the app writes only to its own private inbox",
    "android.permission.REQUEST_INSTALL_PACKAGES": "the app never installs packages",
    "android.permission.SYSTEM_ALERT_WINDOW": "the app never draws over other apps",
    "android.permission.QUERY_ALL_PACKAGES": "sharing uses the system chooser",
}
# The network permission is only defensible as part of the direct-link feature, and the permissions
# around it have to stay narrow. These are the conditions under which INTERNET is tolerated.
WIFI_DIRECT_PERMISSIONS = {
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_WIFI_STATE",
}
# Components that may be exported, and the permission that must guard them (None = launcher).
ALLOWED_EXPORTED = {
    "dev.lightbridge.app.MainActivity": None,
    # Profile installer lets `adb`/Play trigger baseline-profile compilation; guarded by DUMP.
    "androidx.profileinstaller.ProfileInstallReceiver": "android.permission.DUMP",
}


def apkanalyzer() -> str:
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    candidate = os.path.join(home, "cmdline-tools", "latest", "bin", "apkanalyzer")
    return candidate if os.path.exists(candidate) else "apkanalyzer"


def main() -> int:
    apk = sys.argv[1]
    xml = subprocess.run([apkanalyzer(), "manifest", "print", apk],
                         check=True, capture_output=True, text=True).stdout
    root = ET.fromstring(xml)
    errors: list[str] = []

    if root.get("package") != PACKAGE:
        errors.append(f"unexpected package {root.get('package')!r}")

    entries = list(root.iter("uses-permission")) + list(root.iter("uses-permission-sdk-23"))
    perms = {e.get(ANDROID + "name") for e in entries}
    print("requested permissions:", ", ".join(sorted(perms)) or "(none)")
    for p in sorted(perms - ALLOWED_PERMISSIONS):
        errors.append(f"permission not on the allowlist: {p}")
    for p, why in sorted(DENIED_PERMISSIONS.items()):
        if p in perms:
            errors.append(f"permission {p} must never ship: {why}")

    # The one network permission has to stay bound to the feature that needs it.
    if "android.permission.INTERNET" in perms:
        missing = WIFI_DIRECT_PERMISSIONS - perms
        if missing:
            errors.append(
                "INTERNET is present without the Wi-Fi Direct permissions that justify it: "
                + ", ".join(sorted(missing)))
        if "android.permission.NEARBY_WIFI_DEVICES" not in perms:
            errors.append("INTERNET is present but NEARBY_WIFI_DEVICES is not — discovery would be broken")
        print("network posture: INTERNET present, scoped to the peer-to-peer Link tab "
              "(runtime address policy: dev.lightbridge.link.LinkAddressPolicy)")
    for e in entries:
        name, max_sdk, flags = e.get(ANDROID + "name"), e.get(ANDROID + "maxSdkVersion"), e.get(ANDROID + "usesPermissionFlags")
        if name in ("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"):
            if max_sdk != "32":
                errors.append(f"{name.split('.')[-1]} must be capped at API 32, found maxSdkVersion={max_sdk!r}")
        # apkanalyzer prints usesPermissionFlags numerically once the manifest is packaged:
        # NEVER_FOR_LOCATION is 1 shl 16.
        if name == "android.permission.NEARBY_WIFI_DEVICES" and flags not in ("neverForLocation", "0x10000"):
            errors.append(f"NEARBY_WIFI_DEVICES must declare neverForLocation, found {flags!r}")

    app = root.find("application")
    if app is None:
        errors.append("no <application> element")
        app = ET.Element("application")
    for attr, want in (("allowBackup", "false"), ("debuggable", None), ("usesCleartextTraffic", None),
                       ("testOnly", None)):
        got = app.get(ANDROID + attr)
        if want is not None and got != want:
            errors.append(f"android:{attr} must be {want}, found {got!r}")
        if want is None and got == "true":
            errors.append(f"android:{attr}=true must not ship in a release build")
    if root.get("{http://schemas.android.com/apk/res/android}testOnly") == "true":
        errors.append("testOnly APK")

    exported = []
    for kind in ("activity", "activity-alias", "service", "receiver", "provider"):
        for comp in app.iter(kind):
            name = comp.get(ANDROID + "name")
            is_exported = comp.get(ANDROID + "exported")
            has_filter = comp.find("intent-filter") is not None
            if is_exported == "true" or (is_exported is None and has_filter):
                exported.append((kind, name, comp.get(ANDROID + "permission")))
            if kind == "provider":
                if is_exported == "true":
                    errors.append(f"provider {name} is exported")
                if name == "androidx.core.content.FileProvider" and comp.get(
                        ANDROID + "grantUriPermissions") != "true":
                    errors.append("FileProvider must use per-URI grants")
    print("exported components:", exported or "(none)")
    for kind, name, perm in exported:
        if name not in ALLOWED_EXPORTED:
            errors.append(f"exported {kind} not on the allowlist: {name}")
        elif ALLOWED_EXPORTED[name] and perm != ALLOWED_EXPORTED[name]:
            errors.append(f"exported {kind} {name} must be guarded by {ALLOWED_EXPORTED[name]}, has {perm!r}")

    if errors:
        for e in errors:
            print(f"::error::manifest gate: {e}")
        return 1
    print("manifest gate: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
