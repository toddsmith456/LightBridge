# Release acceptance checklist

Unchecked items require actual devices or release-owner action; do not describe them as
passed merely because the project builds. Test both debug and signed, R8-minified release.

## Automated gates
- [ ] `:transfer:test` golden upstream fingerprints and loss/reordering tests pass.
- [ ] Theme and app unit tests, lint, debug and minified release builds pass.
- [ ] Compose smoke tests run on emulator.
- [ ] Dependency/license review complete; manifest contains no Internet permission.

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
- [ ] APK signature verified with private release key; no debug signing in production.
- [ ] Install/upgrade tested; back up release key offline and retain R8 mapping.
- [ ] App name/package ownership and store policy reviewed; privacy disclosure published.
- [ ] Do not advertise APNG export, embedded media playback, localization, or web speed records.
