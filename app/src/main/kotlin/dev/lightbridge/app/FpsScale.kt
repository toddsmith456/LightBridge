// SPDX-License-Identifier: MIT
package dev.lightbridge.app

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The frame-rate control, in tenths of a frame per second.
 *
 * The sender's rate is stored as an integer number of tenths so 0.1 fps is exact and nothing depends
 * on float equality. The slider is *not* linear in rate: a linear 0.1–20 range would put everything
 * below 1 fps inside the first 5% of the track, which makes the slow end impossible to select. The
 * position is squared instead, so the first half of the slider covers 0.1–5 fps and the rest runs up
 * to 20 fps, with every tenth reachable.
 */
internal object FpsScale {

    /** Slowest supported rate: one QR code every ten seconds. */
    const val MIN_TENTHS: Int = 1

    /** Fastest supported rate, matching the previous release's ceiling. */
    const val MAX_TENTHS: Int = 200

    /** 8.0 fps — the rate the app has always recommended for a first attempt. */
    const val DEFAULT_TENTHS: Int = 80

    /** Quarter-steps of a tenth are fine; the value is rounded to a whole tenth. */
    fun positionToTenths(position: Float): Int {
        val clamped = position.coerceIn(0f, 1f)
        val tenths = MIN_TENTHS + (MAX_TENTHS - MIN_TENTHS) * clamped * clamped
        return tenths.roundToInt().coerceIn(MIN_TENTHS, MAX_TENTHS)
    }

    /** Inverse of [positionToTenths], for drawing the slider at a stored value. */
    fun tenthsToPosition(tenths: Int): Float {
        val clamped = tenths.coerceIn(MIN_TENTHS, MAX_TENTHS)
        return sqrt((clamped - MIN_TENTHS).toFloat() / (MAX_TENTHS - MIN_TENTHS))
    }

    /** `8.0 fps`, `0.1 fps`. */
    fun label(tenths: Int): String = "%.1f fps".format(tenths.coerceIn(MIN_TENTHS, MAX_TENTHS) / 10.0)

    /** Milliseconds between frames, so 0.1 fps is a ten-second gap and 8 fps is 1250 ms. */
    fun framePeriodMillis(tenths: Int): Long = 10_000L / tenths.coerceIn(MIN_TENTHS, MAX_TENTHS)

    /** True for rates slow enough that the UI should warn about transfer time. */
    fun isVerySlow(tenths: Int): Boolean = tenths <= 10

    /** Migrates a stored whole-fps value (the old format) to tenths. */
    fun fromWholeFps(fps: Int): Int = (fps.coerceIn(1, 20) * 10).coerceIn(MIN_TENTHS, MAX_TENTHS)
}
