# Youniversal

A production-ready **theme system for Jetpack Compose** with full Material You (Material 3)
support, three first-class background themes — **Light**, **Dark** and **Cream** — and a
component library built to look finished rather than demo-grade.

Drop it into an existing app as the whole UI, or wire it to a switch so users can turn it on
and off. Both modes are supported by the same API.

```kotlin
val theme = rememberYouniversalThemeState()

YouniversalTheme(state = theme) {
    YouniversalScaffold(topBar = { YouniversalTopBar(title = "Home") }) { padding ->
        // your screen
    }
}
```

Licensed under [MIT](LICENSE).

---

## Contents

- [What's in the box](#whats-in-the-box)
- [Requirements](#requirements)
- [Getting started](#getting-started)
- [Using it as a toggle](#using-it-as-a-toggle)
- [The three backgrounds](#the-three-backgrounds)
- [Material You](#material-you)
- [Accessibility](#accessibility)
- [Component library](#component-library)
- [Theming knobs](#theming-knobs)
- [API reference](#api-reference)
- [The demo app](#the-demo-app)
- [How the palette is generated](#how-the-palette-is-generated)
- [Publishing as a library](#publishing-as-a-library)
- [Project layout](#project-layout)
- [Releases](#releases)
- [Building and verifying](#building-and-verifying)
- [License](#license)

---

## What's in the box

| | |
| --- | --- |
| **Two Gradle modules** | `youniversal/` is the reusable theme library (`dev.youniversal.theme`). `app/` is a standalone demo app (`dev.youniversal.demo`) that exercises every feature. |
| **Three background themes** | Light, Dark and Cream — each a complete 48-role Material 3 `ColorScheme`. Cream is a warm, slightly darkened old-paper tone, not a beige afterthought. |
| **Full Material You** | Dynamic colour from the wallpaper on Android 12+, all 48 M3 roles including the twelve *fixed* roles, and a tonal-palette generator so any brand seed produces a consistent scheme. |
| **Toggleable** | `YouniversalTheme(enabled = false) { fallback { ... } }` hands control back to whatever theme the host app already uses. State is observable and persisted. |
| **Persistent settings** | `YouniversalThemeState` stores background style, Material You on/off, contrast level, accent seed, corner scale and font scale in `SharedPreferences`. |
| **Animated transitions** | Colour schemes cross-fade role by role on a 480 ms theme curve; every role is interpolated, so nothing snaps. |
| **40+ components** | Cards, buttons, text fields, switches, chips, sliders, progress, segmented controls, top/bottom bars, FABs, dialogs, snackbars, list rows, avatars, badges, empty states, backdrops. |
| **Accessibility built in** | Every shipped palette is WCAG AA for body text, and a `High` contrast mode pushes the main pairs to AAA. |
| **Zero binary assets** | Launcher icons are vector XML; UI icons are built from `ImageVector` path data. Nothing to license, nothing to compress. |

## Requirements

| | |
| --- | --- |
| Android Gradle Plugin | 8.13.2 |
| Gradle | 8.13 |
| JDK | 17 |
| Kotlin | 2.3.20 |
| Compose BOM | 2026.04.01 (Material 3 1.4.0, Compose 1.11.0) |
| `compileSdk` / `targetSdk` | 36 |
| `minSdk` | 24 (Material You itself activates at 31) |

## Getting started

### Option A — copy the module

Copy the `youniversal/` directory into your project and add it to `settings.gradle.kts`:

```kotlin
include(":youniversal")
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":youniversal"))
}
```

### Option B — publish to Maven

See [Publishing as a library](#publishing-as-a-library), then:

```kotlin
dependencies {
    implementation("dev.youniversal:youniversal-theme:1.0.0")
}
```

### Wrap your UI

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val theme = rememberYouniversalThemeState()
            YouniversalTheme(state = theme) {
                App()
            }
        }
    }
}
```

That single wrapper gives you the colour scheme, typography, shapes, extended colour roles and
system-bar styling. Everything you write inside it with `MaterialTheme.colorScheme.*` picks up
the active theme automatically.

## Using it as a toggle

The point of `enabled` is that Youniversal can live *inside* an app that already has a theme.
When it is off, Youniversal applies no palette of its own: it calls `fallback` instead, which by
default is a plain `MaterialTheme` wrapper. Pass your host theme as `fallback` and it stays in
charge.

`rememberYouniversalThemeState()` persists the choice, so the switch survives a restart.

```kotlin
val theme = rememberYouniversalThemeState()   // persists the user's choice

YouniversalTheme(state = theme) {   // reads state.enabled, and every other setting
    YouniversalApp()
}

// ...and elsewhere in settings:
YouniversalSettingRow(
    title = "Youniversal theme",
    subtitle = "Apply the Youniversal look",
    trailing = {
        YouniversalSwitch(checked = theme.enabled, onCheckedChange = theme::setEnabled)
    },
)
```

To hand off to the theme your app already has, pass it as `fallback`:

```kotlin
YouniversalTheme(
    state = theme,
    fallback = { LegacyAppTheme(content = it) },
) {
    YouniversalApp()
}
```

`fallback` receives the content and is called whenever `enabled` is `false`. The extended colour
roles fall back to a neutral set (`YouniversalExtendedColors.Fallback`) so components that read
`success`/`warning`/`info` never render with an undefined colour.

Anywhere inside the tree you can ask which theme is actually live:

```kotlin
val on = YouniversalTheme.enabled                 // false when the user switched it off
val style = YouniversalTheme.backgroundStyle      // the resolved Auto/Light/Dark/Cream
val dark = YouniversalTheme.isDark
val success = YouniversalTheme.extendedColors.success
```

## The three backgrounds

`YouniversalBackgroundStyle` has four values: `Auto`, `Light`, `Dark`, `Cream`. `Auto` follows
the system setting and maps to Light or Dark. `resolve(isSystemInDarkTheme)` gives you the
concrete style, `isDark` tells you which polarity is active, and `label` is the human-readable
name used by the demo's segmented control.

Key roles as shipped:

| Role | Light | Dark | Cream |
| --- | --- | --- | --- |
| `primary` | `#2455CC` | `#C1C1FE` | `#984723` |
| `onPrimary` | `#FFFFFF` | `#002B75` | `#FFFFFF` |
| `primaryContainer` | `#E1E0FF` | `#013FA4` | `#FFDBCC` |
| `background` | `#F9F9FD` | `#131316` | `#E2D5C7` |
| `onBackground` | `#1B1B1E` | `#E2E2E6` | `#261E13` |
| `surface` | `#F9F9FD` | `#131316` | `#E8DBCC` |
| `surfaceContainerHigh` | `#E8E8EC` | `#292A2C` | `#DCD0C1` |
| `surfaceVariant` | `#E1E2EA` | `#46464D` | `#E6D4C0` |
| `outline` | `#76767E` | `#909098` | `#776856` |
| `error` | `#BD0E19` | `#FEB4A7` | `#BD0E19` |

Cream is generated from its own seeds — a terracotta primary, a muted sand secondary and an
olive tertiary — with paper-toned neutrals, so it reads as warm light rather than as "light
mode with a filter". Its accent is deliberately earthy: `#984723` on `#FFDBCC` containers.

Extended roles (`YouniversalExtendedColors`) add `success`, `warning` and `info` — each with
`on*` and `*Container` variants — plus backdrop and shimmer colours, all defined per background
style.

## Material You

`YouniversalTheme` builds the scheme in this order:

1. **Custom accent seed** — if `state.accentSeed != Color.Unspecified`, a full scheme is
   generated from that seed with `YouniversalTonalPalette`.
2. **Dynamic colour** — if the platform supports it and the user has it on, the wallpaper
   scheme is used. On Cream, the wallpaper's accents are kept but the neutrals are swapped for
   paper tones via `ColorScheme.withCreamNeutrals()`, so Cream still looks like Cream.
3. **Built-in palette** — `youniversalColorScheme(style)` otherwise.

Dynamic colour is read through `dynamicDarkColorScheme()` / `dynamicLightColorScheme()` and
guarded by `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`, so it is inert below Android 12.

All three paths produce every one of the 48 Material 3 roles, including the twelve fixed roles
(`primaryFixed`, `onPrimaryFixedVariant`, …) that Material 3 1.4.0 requires for a consistent
palette. Schemes are built with `lightColorScheme(...)` / `darkColorScheme(...)` using named
arguments and adjusted with `ColorScheme.copy(...)`, so an upstream role addition never breaks
the build.

## Accessibility

- **Body text** (`onSurface` on `background`) is **≥ 4.5:1** in all three shipped palettes — the
  measured floor across the 22 text/surface pairs each palette is checked against is **6.44:1**.
- **High contrast mode** (`YouniversalContrast.High`) pushes the primary text pairs to AAA:
  20.0:1 on Light, 18.5:1 on Dark, 14.6:1 on Cream.
- **Generated palettes** from arbitrary accent seeds are verified too — across the seeds tested,
  the worst body-text ratio is 14.35:1 and the worst button-label ratio is 6.42:1.
- `Color.youniversalReadableContentColor()` picks black or white text for any background by
  relative luminance, and `Color.youniversalContrastAgainst(other)` reports the WCAG ratio.

The claims above are executable: `youniversal/src/test/kotlin/dev/youniversal/theme/YouniversalColorTest.kt`
asserts them, and `tools/palette_lab.py` verifies the shipped palettes independently.

## Component library

Everything lives in `dev.youniversal.theme` and is prefixed `Youniversal`.

| Group | Components |
| --- | --- |
| Scaffold | `YouniversalScaffold`, `YouniversalTopBar`, `YouniversalBottomBar`, `RowScope.YouniversalNavigationItem`, `YouniversalSystemBars`, `YouniversalBackdrop` |
| Surfaces | `YouniversalCard`, `YouniversalElevatedCard`, `YouniversalOutlinedCard`, `YouniversalHeroCard`, `youniversalCardBorder()` |
| Buttons | `YouniversalButton`, `YouniversalElevatedButton`, `YouniversalOutlinedButton`, `YouniversalTextButton`, `YouniversalTonalButton` — all with a `loading` state |
| Inputs | `YouniversalTextField`, `YouniversalOutlinedTextField`, `YouniversalSwitch`, `YouniversalCheckbox`, `YouniversalRadioButton`, `YouniversalSlider` |
| Selection | `YouniversalSegmentedControl<T>`, `YouniversalFilterChip`, `YouniversalAssistChip`, `YouniversalSuggestionChip` |
| Lists | `YouniversalSectionHeader`, `YouniversalDivider`, `YouniversalSettingRow`, `YouniversalAvatar`, `YouniversalBadge` |
| Floating | `YouniversalFab`, `YouniversalExtendedFab`, `YouniversalIconButton` |
| Feedback | `YouniversalAlertDialog`, `YouniversalSnackbarHost`, `YouniversalEmptyState`, `YouniversalProgressBar` |
| Inspection | `youniversalColorRoles()`, `YouniversalColorSwatch`, `YouniversalPalettePreview` |

`YouniversalTopBar` takes a `TopAppBarScrollBehavior`, which Material 3 still marks experimental,
so files that call it need `@file:OptIn(ExperimentalMaterial3Api::class)`. The demo does exactly
that in `DemoApp.kt`.

Components take `ImageVector` rather than pulling in `material-icons-extended`, so you stay free
to use whatever icon set you like. `YouniversalAvatar` shows either initials or an icon:
`YouniversalAvatar(initials = "TS")`, `YouniversalAvatar(icon = SyncIcon)`. `YouniversalBackdrop` draws three gradient treatments —
`Flat`, `Aurora` and `Vignette` — behind your content for a bit of depth without an image
asset.

```kotlin
YouniversalCard(onClick = { /* … */ }) {
    YouniversalSettingRow(
        title = "Sync over mobile data",
        subtitle = "Uses your data plan",
        leadingIcon = SyncIcon,   // any ImageVector
        trailing = { YouniversalSwitch(checked = sync, onCheckedChange = { sync = it }) },
    )
}
```

## Theming knobs

`YouniversalThemeState` is observable, persisted, and safe to hoist:

| Property | Setter | Persisted | Range |
| --- | --- | --- | --- |
| `enabled` | `setEnabled` | yes | `Boolean` |
| `backgroundStyle` | `setBackgroundStyle` | yes | `YouniversalBackgroundStyle` |
| `dynamicColor` | `setDynamicColor` | yes | `Boolean` (effective on API 31+) |
| `contrast` | `setContrast` | yes | `Default` / `High` |
| `accentSeed` | `setAccentSeed` | yes | any `Color`, or `Color.Unspecified` for brand |
| `cornerScale` | `setCornerScale` | yes | `0f`–`2f`, `1f` = 8/12/18/24/32 dp |
| `fontScale` | `setFontScale` | yes | `0.7f`–`2f` |
| `animateTransitions` | `setAnimateTransitions` | yes | `Boolean` |
| `fontFamily` | `setFontFamily` | no | `FontFamily?` |

`reset()` returns everything to defaults. `cornerScale = 0f` gives a sharp, squared-off look;
`2f` doubles every radius including the `Pill` shape.

Non-persisted, call-site overrides are also available on `YouniversalTheme` itself
(`backgroundStyle`, `contrast`, `accentSeed`, `cornerScale`, `fontScale`, `fontFamily`,
`animateTransitions`, `styleSystemBars`), which is handy for previews and one-off screens.

## API reference

**Objects** — `YouniversalTheme` (composition locals + accessors), `YouniversalMetrics`
(spacing, control and bar heights, elevation, hairline width), `YouniversalShapes`,
`YouniversalMotion` (durations, easing curves, springs, the theme colour transition spec),
`YouniversalTonalPalette` (the tone generator).

**Colour schemes** — `youniversalLightColorScheme()`, `youniversalDarkColorScheme()`,
`youniversalCreamColorScheme()`, `youniversalColorScheme(style)`,
`ColorScheme.youniversalHighContrast(isDark)`, `ColorScheme.withCreamNeutrals(cream)`.

**Color utilities** — `Color.youniversalLighten(amount)`, `youniversalDarken(amount)`,
`youniversalAt(alpha)`, `youniversalRelativeLuminance()`, `youniversalReadableContentColor()`,
`youniversalContrastAgainst(other)`.

**Design tokens** — `youniversalShapes(cornerScale)`, `youniversalTypography(fontFamily, fontScale)`.

**Extended colours** — `YouniversalExtendedColors`, `youniversalExtendedColors(style, isSystemInDarkTheme)`.

**State** — `YouniversalThemeState`, `rememberYouniversalThemeState()`.

**Preview support** — `@YouniversalPreview`.

45 public composables and 16 public utility functions — 72 top-level public declarations across 28 files.

## The demo app

`app/` is a real, installable Android app whose only job is to show Youniversal off. Five
sections, reachable from the bottom bar:

| Section | What it demonstrates |
| --- | --- |
| **Overview** | Live hero, a style preview that redraws as you change settings, and a card listing exactly which palette source is in effect (baseline / accent seed / Material You / Material You + Cream neutrals). |
| **Components** | Every button variant, a button with a real async `loading` state, progress, three text-field variants, filter/assist/suggestion chips, switches, checkboxes, radios, sliders, two segmented controls, FABs and a dialog. |
| **Surfaces** | All four card styles, the hero card, list rows with avatars and badges, the three backdrop treatments side by side, and an empty state. |
| **Type & colour** | The 15-step type scale, the full palette preview, extended-role swatches, and a live contrast table that badges each pair AA/AAA as the theme changes. |
| **About** | Feature list, an integration snippet, the MIT licence text, and the active theme state. |

A theme panel is reachable from the top bar and drives everything: background style, contrast,
Material You, six accent seeds, corner scale, font scale, animation and reset. Changing any of
them re-themes the whole app in place — including the panel itself.

The demo declares no icon dependency: every glyph is built from `ImageVector` path data in
`DemoIcons.kt`, and the launcher icon is vector XML.

## How the palette is generated

`tools/palette_lab.py` is the source of truth. It generates tones in CIE L\*a\*b\* (D65):

- the seed's hue is preserved and `L*` is forced to the requested tone;
- chroma is reduced by 3% per iteration until the result is inside the sRGB gamut;
- tone 0 is exactly black and tone 100 exactly white, matching Material 3.

The Kotlin implementation in `YouniversalTonalPalette.tone()` is byte-identical to the Python,
which is what makes the two verifiable against each other.

```bash
python3 tools/palette_lab.py     # prints the three schemes and their contrast reports
```

`YouniversalPalette.kt` is generated output — regenerate it with that script rather than editing
the hex literals by hand.

## Publishing as a library

`youniversal/build.gradle.kts` is already configured:

```bash
./gradlew :youniversal:publishToMavenLocal
```

Coordinates are `dev.youniversal:youniversal-theme:1.0.0` with a sources jar and an MIT-licensed
POM.

## Project layout

```
Youniversal/
├── app/                      demo app (dev.youniversal.demo)
│   └── src/main/kotlin/…/demo/
│       ├── MainActivity.kt
│       ├── DemoApp.kt        navigation + theme wiring
│       ├── DemoIcons.kt      vector icons built from path data
│       └── ui/               Overview, Components, Surfaces, Type & colour, About, theme panel
├── youniversal/              the library (dev.youniversal.theme)
│   ├── src/main/kotlin/…/theme/    28 files
│   └── src/test/kotlin/…/theme/    YouniversalColorTest.kt
├── gradle/libs.versions.toml pinned toolchain and dependencies
└── tools/palette_lab.py      palette generator + WCAG verifier
```

## Releases

Every version is published as a GitHub Release carrying everything needed to install or consume
it, so there is nothing to build yourself.

**[Latest release →](https://github.com/toddsmith456/Youniversal/releases/latest)**

| Asset | What it is |
| --- | --- |
| `youniversal-demo-<version>-release.apk` | The demo app, minified and resource-shrunk. Sideload it with `adb install -r <file>` or by tapping it on the device. **Signed with the standard Android debug key**, so it installs fine but is not a Play Store upload. |
| `youniversal-demo-<version>-debug.apk` | The same app unminified, with the Compose tooling included — use this one for readable stack traces. |
| `youniversal-theme-<version>.aar` | The library. Drop it in `app/libs/` or publish it to your own Maven repository. |
| `youniversal-theme-<version>-sources.jar`, `.pom` | Sources, and the POM for `dev.youniversal:youniversal-theme:<version>`. |
| `Youniversal-<version>-source.zip` | The complete source tree at that tag. GitHub attaches its own `Source code (zip)` / `(tar.gz)` archives to the release as well. |
| `youniversal-theme-<version>-unit-test-report.zip` | The JVM unit-test report for the colour engine. |
| `SHA256SUMS.txt` | SHA-256 of every asset above. |

The APKs target `minSdk 24` / `targetSdk 36`, and Material You (dynamic colour) activates on
Android 12 or newer.

### Cutting a release

The version lives in exactly one place — `youniversal` in `gradle/libs.versions.toml` — and the
tag must match it, so the APK's `versionName`, the Maven coordinates and the tag cannot drift
apart. To publish version *X*:

```bash
# 1. Set youniversal = "X" in gradle/libs.versions.toml, commit it, and merge it into main.
# 2. Tag that commit and push the tag — pushing the tag is what starts the release.
git tag -a vX -m "Youniversal vX" && git push origin vX
```

[`.github/workflows/release.yml`](.github/workflows/release.yml) then re-runs the contrast and
generator-parity checks, runs the unit tests, builds `:app:assembleDebug`, `:app:assembleRelease`
and the library together with its sources jar and POM, and attaches every file in the table above
to the release. A tag that disagrees with `libs.versions.toml` fails the run before anything is
published. The workflow can also be started by hand from **Actions ▸ Release ▸ Run workflow**,
which creates the tag at the commit it is dispatched on if that tag does not exist yet.

## Building and verifying

Continuous integration lives in [`.github/workflows/ci.yml`](.github/workflows/ci.yml): a
`palette` job (Python, no JVM) that asserts the contrast floor and that
`YouniversalPalette.kt` still matches the generator, and a `build` job that compiles everything
and uploads both demo APKs. Locally:

```bash
gradle wrapper --gradle-version 8.13     # generates the wrapper if it isn't checked in
./gradlew :app:assembleDebug             # build and install the demo
./gradlew :youniversal:testDebugUnitTest # run the color-engine tests
./gradlew :youniversal:assembleRelease   # build the publishable AAR
```

**Verification status of this repository.** The Kotlin sources were checked by parsing every
file with a Kotlin grammar, by cross-referencing every Youniversal and demo symbol against the
declarations that actually exist (imports, composable calls, enum members, object members), and
by confirming that every capitalised identifier is either imported or declared locally. All 11
XML files and the version catalog parse. The palette maths and every assertion in
`YouniversalColorTest.kt` were executed against `tools/palette_lab.py` and pass.

What could **not** be run in the environment where this was written: `./gradlew` itself. There
is no JVM, no Android SDK, and no access to the Maven repositories, so the project was never
compiled end to end here. `.github/workflows/ci.yml` closes that gap — on every push it runs the
contrast and generator-parity checks, the unit tests, and `assembleDebug` plus a minified
`assembleRelease` (which also exercises `app/proguard-rules.pro`) on JDK 17 with Gradle 8.13.
If the first CI run fails, the compiler is right and the code is wrong.

## License

MIT — see [LICENSE](LICENSE). Applies to both the `youniversal` library and the demo app.
