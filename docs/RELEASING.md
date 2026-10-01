# Releasing LightBridge (signed)

Production APKs are signed **only** in the `release` GitHub environment, with a key that exists
nowhere except your offline backup and GitHub's encrypted secret store. Gradle's release output is
deliberately unsigned; the debug key is never used and the pipeline has no fallback to it.

## One-time setup (do this on your own machine, not in chat)

```sh
gh auth login                       # needs admin rights on the repository
tools/setup-release-signing.sh      # generates the key and uploads it
```

The script:

1. creates a 4096-bit RSA PKCS12 keystore in `~/.lightbridge-signing/` (mode 700), with a random
   password written next to it in `PASSWORDS-BACK-UP-OFFLINE.txt`;
2. uploads `LIGHTBRIDGE_KEYSTORE_BASE64`, `LIGHTBRIDGE_KEYSTORE_PASSWORD`,
   `LIGHTBRIDGE_KEY_PASSWORD` and `LIGHTBRIDGE_KEY_ALIAS` as **secrets of the `release` environment**
   through stdin (nothing on a command line or in logs);
3. pins the public certificate fingerprint as the repository **variable** `LIGHTBRIDGE_CERT_SHA256`.
   Every release verifies the signed APK against it, so a swapped or wrong key fails the build.

To use a key you already have: `tools/setup-release-signing.sh --existing path/to/key.jks`.

Then, in **Settings → Environments → release**, add yourself as a required reviewer (recommended)
and restrict deployment to tags `v*`. **Back up `~/.lightbridge-signing/` offline** (encrypted
drive plus password manager). Losing the key means no future update can install over existing
installs; leaking it means anyone can ship a "genuine" LightBridge.

> Never paste the keystore, its base64, or the passwords into chat, issues, or commits.
> `.gitignore` already blocks `*.jks`, `*.keystore` and `keystore.properties`.

## Cutting a release

```sh
git checkout main && git pull
git tag v0.1.0 && git push origin v0.1.0     # tag must be on main, vMAJOR.MINOR.PATCH[-pre]
```

`.github/workflows/release.yml` then runs, in order, and stops at the first failure:

| Job | Gate |
| --- | --- |
| `validate` | tag on `main`; palette parity; `:transfer:test`, theme + app unit tests; `lintDebug`, `lintRelease`; minified release build; **manifest gate** (no INTERNET, allowlisted permissions/exported components, no debuggable/cleartext/backup); **dependency audit** (OSV advisories + license allowlist, unreachable OSV = failure); packaged version equals the tag |
| `sign` | all four secrets and the pinned fingerprint present; `zipalign`; `apksigner` v1+v2+v3; **verify**: exactly one signer, not a debug certificate, SHA-256 equals the pinned fingerprint |
| `device` | the *signed* APK is installed, upgraded in place, launched, rotated and backgrounded on Android 7 (24), 12 (31), 15 (35) and 16 (36) emulators; no crash/ANR; not debuggable; a debug-signed build is **rejected** over it (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) |
| `publish` | GitHub Release with `LightBridge-X.Y.Z.apk`, `.sha256`, R8 mapping, dependency inventory |

`versionName` comes from the tag and `versionCode` is `major·1 000 000 + minor·10 000 + patch·100`
(strictly increasing). Pre-release tags (`v0.2.0-rc.1`) publish as GitHub pre-releases.

CI (`ci.yml`) exercises the same `sign_apk.sh` / `verify_signature.sh` / `device_smoke.sh` on every
push with a **throwaway** key, including negative cases (wrong fingerprint, missing secrets,
debug-signed APK), so the release path is tested long before a real key exists.

## What automation cannot replace

Emulators do not prove optical interoperability. Before announcing a release, complete the
physical-device items in [RELEASE-CHECKLIST.md](RELEASE-CHECKLIST.md) (two-device QR transfer,
real cameras, glare/focus, TalkBack, low-RAM devices, current Decimen interoperability) and record
the results in the release notes. Verify a downloaded APK yourself:

```sh
sha256sum -c LightBridge-0.1.0.apk.sha256
apksigner verify --print-certs LightBridge-0.1.0.apk   # SHA-256 must equal LIGHTBRIDGE_CERT_SHA256
```

Keep every release's R8 mapping file; it is attached to the release.
