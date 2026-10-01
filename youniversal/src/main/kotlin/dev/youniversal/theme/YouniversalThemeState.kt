// SPDX-License-Identifier: MIT
package dev.youniversal.theme

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily

/**
 * Holds every Youniversal setting, persists it to `SharedPreferences`, and exposes each value as
 * Compose state so a settings screen re-themes the app the moment a control changes.
 *
 * Properties are read-only and observable; the `set…` functions are the mutators, because they
 * also write the new value to disk. (Making the properties `var` with a private setter would
 * generate a synthetic `setEnabled` that collides with the explicit one on the JVM.)
 *
 * Writes are coalesced: a slider drag changes a value 60 times a second, and committing every one
 * of those to `SharedPreferences` was visible as jank. Compose state still updates immediately (the
 * UI is live), while at most one editor is applied per [WRITE_DELAY_MS]. Call [flushWrites] from
 * `onPause` so nothing is lost when the app leaves the foreground.
 *
 * ```
 * val themeState = rememberYouniversalThemeState()
 * YouniversalTheme(state = themeState) {
 *     Switch(checked = themeState.enabled, onCheckedChange = themeState::setEnabled)
 * }
 * ```
 */
@Stable
public class YouniversalThemeState internal constructor(context: Context) {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val enabledState = mutableStateOf(preferences.getBoolean(KEY_ENABLED, true))
    private val backgroundStyleState =
        mutableStateOf(readEnum(KEY_STYLE, YouniversalBackgroundStyle.Auto))
    private val dynamicColorState = mutableStateOf(preferences.getBoolean(KEY_DYNAMIC, true))
    private val contrastState = mutableStateOf(readEnum(KEY_CONTRAST, YouniversalContrast.Default))
    private val accentSeedState = mutableStateOf(readSeed())
    private val cornerScaleState =
        mutableStateOf(preferences.getFloat(KEY_CORNERS, 1f).coerceIn(0f, 2f))
    private val fontScaleState =
        mutableStateOf(preferences.getFloat(KEY_FONT_SCALE, 1f).coerceIn(0.7f, 2f))
    private val animateTransitionsState = mutableStateOf(preferences.getBoolean(KEY_ANIMATE, true))
    private val fontFamilyState = mutableStateOf<FontFamily?>(null)

    /** Master switch. When false the host app's own theme renders the content. */
    public val enabled: Boolean
        get() = enabledState.value

    /** The background theme. Defaults to following the system. */
    public val backgroundStyle: YouniversalBackgroundStyle
        get() = backgroundStyleState.value

    /** Material You / wallpaper color. Requires Android 12+. */
    public val dynamicColor: Boolean
        get() = dynamicColorState.value

    /** Default or high contrast. */
    public val contrast: YouniversalContrast
        get() = contrastState.value

    /** Custom accent seed, or [Color.Unspecified] to use the Youniversal accent. */
    public val accentSeed: Color
        get() = accentSeedState.value

    /** Corner radius multiplier, 0f–2f. */
    public val cornerScale: Float
        get() = cornerScaleState.value

    /** Type scale multiplier, 0.7f–2f. */
    public val fontScale: Float
        get() = fontScaleState.value

    /** Morph colors when the theme changes. */
    public val animateTransitions: Boolean
        get() = animateTransitionsState.value

    /**
     * Typeface override. Not persisted — a font is an object, not a preference — so restore it at
     * startup with [setFontFamily] if your app ships one.
     */
    public val fontFamily: FontFamily?
        get() = fontFamilyState.value

    public fun setEnabled(value: Boolean) {
        enabledState.value = value
        scheduleWrite(KEY_ENABLED, value)
    }

    public fun setBackgroundStyle(value: YouniversalBackgroundStyle) {
        backgroundStyleState.value = value
        scheduleWrite(KEY_STYLE, value.name)
    }

    public fun setDynamicColor(value: Boolean) {
        dynamicColorState.value = value
        scheduleWrite(KEY_DYNAMIC, value)
    }

    public fun setContrast(value: YouniversalContrast) {
        contrastState.value = value
        scheduleWrite(KEY_CONTRAST, value.name)
    }

    /** Pass [Color.Unspecified] to clear the seed and return to the Youniversal accent. */
    public fun setAccentSeed(value: Color) {
        accentSeedState.value = value
        scheduleWrite(
            KEY_SEED,
            if (value == Color.Unspecified) null else value.toArgb().toLong() and 0xFFFFFFFFL,
        )
    }

    public fun setCornerScale(value: Float) {
        val coerced = value.coerceIn(0f, 2f)
        cornerScaleState.value = coerced
        scheduleWrite(KEY_CORNERS, coerced)
    }

    public fun setFontScale(value: Float) {
        val coerced = value.coerceIn(0.7f, 2f)
        fontScaleState.value = coerced
        scheduleWrite(KEY_FONT_SCALE, coerced)
    }

    public fun setAnimateTransitions(value: Boolean) {
        animateTransitionsState.value = value
        scheduleWrite(KEY_ANIMATE, value)
    }

    public fun setFontFamily(value: FontFamily?) {
        fontFamilyState.value = value
    }

    /** Returns every persisted setting to its default. [fontFamily] is cleared too. */
    public fun reset() {
        pendingWrites.clear()
        handler.removeCallbacks(flushPending)
        preferences.edit().clear().apply()
        enabledState.value = true
        backgroundStyleState.value = YouniversalBackgroundStyle.Auto
        dynamicColorState.value = true
        contrastState.value = YouniversalContrast.Default
        accentSeedState.value = Color.Unspecified
        cornerScaleState.value = 1f
        fontScaleState.value = 1f
        animateTransitionsState.value = true
        fontFamilyState.value = null
    }

    // ---------------------------------------------------------------- persistence

    private val handler = Handler(Looper.getMainLooper())
    private val pendingWrites = LinkedHashMap<String, Any?>()
    private val flushPending = Runnable { flushWrites() }

    /** Records a value to persist; at most one editor is applied per coalescing window. */
    private fun scheduleWrite(key: String, value: Any?) {
        pendingWrites[key] = value
        handler.removeCallbacks(flushPending)
        handler.postDelayed(flushPending, WRITE_DELAY_MS)
    }

    /**
     * Writes everything that is pending right now.
     *
     * Call from `onPause`: a drag that ends as the app is backgrounded must not lose its last value.
     */
    public fun flushWrites() {
        handler.removeCallbacks(flushPending)
        if (pendingWrites.isEmpty()) return
        val editor = preferences.edit()
        for ((key, value) in pendingWrites) {
            when (value) {
                null -> editor.remove(key)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
            }
        }
        pendingWrites.clear()
        editor.apply()
    }

    /** True when a value is waiting to be written; used by tests and diagnostics. */
    public val hasPendingWrites: Boolean get() = pendingWrites.isNotEmpty()

    private fun readSeed(): Color {
        if (!preferences.contains(KEY_SEED)) return Color.Unspecified
        return Color(preferences.getLong(KEY_SEED, 0L))
    }

    private inline fun <reified T : Enum<T>> readEnum(key: String, default: T): T {
        val name = preferences.getString(key, null) ?: return default
        return enumValues<T>().firstOrNull { it.name == name } ?: default
    }

    private companion object {
        const val PREFERENCES_NAME = "youniversal_theme"
        const val KEY_ENABLED = "enabled"
        const val KEY_STYLE = "background_style"
        const val KEY_DYNAMIC = "dynamic_color"
        const val KEY_CONTRAST = "contrast"
        const val KEY_SEED = "accent_seed"
        const val KEY_CORNERS = "corner_scale"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_ANIMATE = "animate_transitions"

        /** Long enough to swallow a drag, short enough that a crash loses nothing meaningful. */
        const val WRITE_DELAY_MS = 400L
    }
}

/** Creates (and remembers) a [YouniversalThemeState] backed by `SharedPreferences`. */
@Composable
public fun rememberYouniversalThemeState(
    context: Context = LocalContext.current,
): YouniversalThemeState = remember(context.applicationContext) { YouniversalThemeState(context) }
