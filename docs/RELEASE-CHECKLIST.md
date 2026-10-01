# Release acceptance checklist

Unchecked items require actual devices or release-owner action; do not describe them as
passed merely because the project builds. Test both debug and signed, R8-minified release.

## Automated gates
Verified by CI run [36805244246](https://github.com/toddsmith456/Youniversal/actions/runs/36805244246) on this source; they re-run on every push and again
inside `release.yml` before anything is signed.
- [x] `:transfer:test` golden upstream fingerprints and loss/reordering tests pass.
- [x] Theme and app unit tests, `lintDebug` + `lintRelease`, debug and minified release builds pass.
- [x] Compose smoke tests pass on emulators: Android 7 (API 24), 12 (31), 15 (35), 16 (36).
- [x] Packaged-manifest gate: no INTERNET, permissions limited to CAMERA, only the launcher activity (and the
      DUMP-guarded profile installer) exported, not debuggable, backup/cleartext off (`tools/release/manifest_gate.py`).
- [x] Dependency advisories (OSV) and licenses on the shipped classpath: none open, all Apache-2.0/BSD
      (`tools/release/dependency_audit.py`; inventory is attached to each release).
- [x] Signing pipeline self-test with a throwaway key, including rejection of a wrong pinned fingerprint,
      missing secrets, and a debug-signed APK (`tools/release/ephemeral_sign.sh`).
- [x] Minified release APK installs, upgrades in place, launches, survives rotate/background on API 24/31/35/36
      emulators, and a different-key (debug) build is rejected with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

## Two-device optics (Android 7, 12, 16 coverage)
- [ ] Android → Android: UTF-8 text, random binary, gzip-friendly document, photo.
- [ ] MIT Decimen v0.3.0 browser → Android and Android → browser; compare external SHA-256.
- [ ] Current Decimen interoperability tested separately; record version, do not assume.
- [ ] K=1, odd block sizes from browser, 512/1024/2048-byte Android blocks.
- [ ] 1/2/4 QR codes; portrait/landscape, glare, autofocus, darkness, 2–20 fps.
- [ ] Lose frames, leave/re-enter camera view, pause/resume, duplicates, wrong stream.
- [ ] 64 MiB incompressible file in Dense mode on low- and high-RAM devices.

## Lifecycle and storage
- [ ] Rotation while preparing/sending/receiving; Home, lockscreen, background/foreground.
- [ ] Camera permission denied, permanently denied, revoked in settings; no-camera device.
- [ ] Front fallback, torch unavailable, busy camera, zoom supported/unsupported.
- [ ] Process kill: partial transfer resets honestly; verified inbox survives.
- [ ] Out-of-space, full inbox, cloud document provider errors, canceled pick/save.
- [ ] Save As and share preserve file bytes; deleting inbox does not delete exported copy.
- [ ] Screen brightness and wake policy restore on pause/navigation/errors.

## Accessibility and distribution
- [ ] TalkBack, 200% system text size, landscape, tablet, dark/light/cream/high contrast.
- [ ] QR quiet zones always white; codes fit on every supported screen/density setting.
- [ ] **Release owner:** run `tools/setup-release-signing.sh`; the first `v*` tag then signs, verifies the
      pinned certificate, and device-tests the signed APK (see [RELEASING.md](RELEASING.md)). No debug signing.
- [ ] **Release owner:** back up the release key offline; retain the R8 mapping attached to each release.
- [ ] Install/upgrade of the published, signed APK tested on at least one physical device.
- [ ] App name/package ownership and store policy reviewed; privacy disclosure published.
- [ ] Do not advertise APNG export, embedded media playback, localization, or web speed records.
