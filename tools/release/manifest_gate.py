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

# Permissions the packaged app may request. Anything else — especially INTERNET — is a failure.
ALLOWED_PERMISSIONS = {
    "android.permission.CAMERA",
    # Added automatically by AndroidX for non-exported dynamic receivers (signature-level, own package).
    f"{PACKAGE}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
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

    perms = {e.get(ANDROID + "name") for e in root.iter("uses-permission")}
    perms |= {e.get(ANDROID + "name") for e in root.iter("uses-permission-sdk-23")}
    print("requested permissions:", ", ".join(sorted(perms)) or "(none)")
    for p in sorted(perms - ALLOWED_PERMISSIONS):
        errors.append(f"permission not on the allowlist: {p}")
    if "android.permission.INTERNET" in perms:
        errors.append("INTERNET permission present — LightBridge must stay offline")

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
