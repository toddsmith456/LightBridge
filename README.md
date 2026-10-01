# LightBridge

**A little light. A direct connection.**

An offline Android file-transfer app built in Kotlin and Jetpack Compose. One device
shows animated QR codes; another reconstructs the file with its camera. No accounts,
network, Bluetooth, server, or pairing. The app does not request Internet permission.

LightBridge combines the MIT-licensed **Youniversal** Material You theme system with a
native Kotlin port of **Decimen Optical Transfer v0.3.0**'s MIT-licensed wire protocol.
This is an independent application, not an official Decimen product.

## Features

- Native file picker and text snippets; arbitrary non-empty files up to **64 MiB**, with a lower safe limit on low-heap devices.
- Binary QR frames, robust-soliton LT fountain coding, dropped/duplicate/out-of-order
  frame recovery, opportunistic gzip, filename and MIME preservation.
- FNV-1a container verification **and SHA-256 file verification** before saving anything.
- On-device **CameraX + ZXing** receiver; no proprietary or downloaded scanner models.
  Rear camera with front-camera fallback, torch (when available), and zoom.
- Adjustable 2–20 fps target, three QR densities, and one/two/four displayed QR codes.
- Verified-file inbox (256 MiB limit), Save As through Android's Storage Access Framework,
  and explicit sharing through a narrowly scoped FileProvider.
- All Youniversal controls: system/light/dark/cream backgrounds, Android 12+ Material You,
  seed accents, contrast, corner radius, text scale, animation, reset, and theme opt-out.
- Adaptive launcher icon with Android 13+ themed monochrome support.
- Rotation-safe transfer ViewModel, lifecycle-paused rendering/camera, restored brightness,
  permission denial recovery, bounded input/decompression, decoder resource limits.

## Using it

1. Install the debug APK from this branch's **CI → lightbridge-debug** artifact on Android
   7.0+ (API 24). Debug builds are for evaluation, not a production signing identity.
2. On the sender, choose **Send → Choose a file**, or enter text.
3. On the other device, choose **Receive**, allow the camera, and point at the full QR.
4. Keep sending until the receiver says **Every byte, verified**. Use **Save as…** or
   **Share**. Then tap **Finish** on the sender; there is no return channel.

Start with one Balanced QR at 8 fps. Try Easy and 4 fps if decoding is unreliable.
Use Dense for large files: the protocol's 65,535-block ceiling limits incompressible
files to about 32 MiB in Easy mode and just under 64 MiB in Balanced mode. Multiple
QR codes need a sufficiently large screen. Optical transfer is slower than a cable;
The app additionally limits transfers to one eighth of the Android process heap (shown
on the Send screen), and caps its pending decoder graph. 64 MiB is a size ceiling, not a promise of practical speed or memory availability on
low-RAM devices. Decoder limits can stop unusually expensive transfers with an error.

## Compatibility and scope

The reference is pinned to **v0.3.0 / `29cba8fa25dd160c8b6aa18fe3b48fbc5bde2e36`**,
not current Decimen (v0.4.0+ is AGPL). No AGPL implementation or WASM binary is used.
Kotlin golden-vector tests pin upstream distributions, subsets, and encoded-stream
fingerprints rather than merely testing our encoder against our own decoder.

The native app implements the transfer core. It does **not** include the website's
APNG/PNG-sequence export, media player, benchmark/diagnostics suite, PWA packaging,
or twelve-language UI. This first Android UI is English. Current-web and real-camera
interoperability must be tested on devices before a production release; see the
[release checklist](docs/RELEASE-CHECKLIST.md). Do not assume v0.4.0+ remains compatible.

Partial progress survives rotation and backgrounding while the process lives, **not
process termination**. Completed files persist until deleted/uninstalled. There is no
background camera service. Changing QR density affects the next prepared file.

## Build & test

Requirements: JDK 17, Android SDK platform 36, SDK licenses accepted. Dependencies are
pinned in `gradle/libs.versions.toml` and module build scripts.

```sh
# Set ANDROID_HOME or add sdk.dir=/your/android/sdk to local.properties
./gradlew :transfer:test :youniversal:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:lintDebug :app:assembleDebug :app:assembleRelease
python3 tools/palette_lab.py --verify
```

The checked-in Gradle wrapper uses Gradle 8.13. CI runs tests, lint, palette checks,
and debug + minified release assembly. Output:

- `app/build/outputs/apk/debug/app-debug.apk` — installable development build
- `app/build/outputs/apk/release/app-release-unsigned.apk` — **unsigned** release

For a production release, sign with your own securely managed release key using
Android's `apksigner` (or configure a private signing pipeline). Never ship with the
public debug identity or commit signing keys. The manual release-candidate workflow
produces unsigned APK/AAB artifacts and a shrinker mapping; it does not publish a
GitHub/Play release or claim the device checklist has passed.

## Architecture

| Module / file | Responsibility |
| --- | --- |
| `transfer/` | Android-free wire codec, gzip container, checksums, deterministic fountain encoder and bounded peeling decoder; JVM tests |
| `app/TransferViewModel` | SAF import, worker-thread QR generation, serialized frame ingestion, private verified-file persistence |
| `app/CameraScanner` | Lifecycle-bound CameraX, bounded analysis queue, raw ZXing byte-segment extraction |
| `app/LightBridgeApp` | Native Compose Home, Send, Receive, Inbox, Settings; accessible theme-driven surfaces |
| `youniversal/` | Reusable MIT theme library, retained from this repository |

The original theme library documentation is retained at [docs/YOUNIVERSAL.md](docs/YOUNIVERSAL.md).
Protocol/security details: [docs/PROTOCOL.md](docs/PROTOCOL.md), [SECURITY.md](SECURITY.md).

## Privacy

**Offline is not encrypted.** Any nearby camera can receive the visible stream. Hashes
verify bytes, not sender identity. No telemetry or accounts. Raw camera images are
not persisted. Verified files live in private app storage; backup is disabled. Sharing
hands a file to another app, which may upload it. Deleting is not secure erasure.

## License

App and protocol-port source: **MIT**, see [LICENSE](LICENSE). Original attributions
and dependency licenses are preserved in [NOTICE](NOTICE), [docs/licenses](docs/licenses),
and inside the app's Settings. Dependencies retain their own licenses (notably Apache
2.0 for AndroidX, Kotlin, and ZXing). The existing Youniversal library remains MIT.
