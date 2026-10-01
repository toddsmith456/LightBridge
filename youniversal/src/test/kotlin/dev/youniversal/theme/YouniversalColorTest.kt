// SPDX-License-Identifier: MIT
package dev.youniversal.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the color engine.
 *
 * They run on the JVM — no emulator needed — because the palette code is pure Kotlin:
 *
 * ```
 * ./gradlew :youniversal:testDebugUnitTest
 * ```
 *
 * These are the executable form of the contrast guarantees in the README.
 */
class YouniversalColorTest {

    private fun schemes(): List<Pair<String, ColorScheme>> =
        listOf(
            "light" to youniversalLightColorScheme(),
            "dark" to youniversalDarkColorScheme(),
            "cream" to youniversalCreamColorScheme(),
        )

    // ------------------------------------------------------------------ tonal generator

    @Test
    fun toneEndpointsAreBlackAndWhite() {
        val seed = YouniversalTonalPalette.DefaultSeed
        assertEquals(Color.Black, YouniversalTonalPalette.tone(seed, 0))
        assertEquals(Color.White, YouniversalTonalPalette.tone(seed, 100))
    }

    @Test
    fun tonesGetMonotonicallyLighter() {
        val seed = YouniversalTonalPalette.DefaultSeed
        var previous = -1f
        for (tone in 0..100 step 5) {
            val luminance = YouniversalTonalPalette.tone(seed, tone).youniversalRelativeLuminance()
            assertTrue("tone $tone is darker than the tone before it", luminance >= previous)
            previous = luminance
        }
    }

    @Test
    fun chromaScaleZeroProducesANeutralRamp() {
        val seed = YouniversalTonalPalette.DefaultSeed
        val neutral = YouniversalTonalPalette.tone(seed, 40, chromaScale = 0f)
        val spread = maxOf(neutral.red, neutral.green, neutral.blue) -
            minOf(neutral.red, neutral.green, neutral.blue)
        assertTrue("expected an achromatic gray, got $neutral", spread < 0.01f)
    }

    @Test
    fun outOfRangeTonesAreClamped() {
        val seed = YouniversalTonalPalette.DefaultSeed
        assertEquals(YouniversalTonalPalette.tone(seed, 0), YouniversalTonalPalette.tone(seed, -40))
        assertEquals(YouniversalTonalPalette.tone(seed, 100), YouniversalTonalPalette.tone(seed, 250))
    }

    // ------------------------------------------------------------------ shipped palettes

    @Test
    fun everyShippedPaletteMeetsWcagAaForBodyText() {
        for ((name, scheme) in schemes()) {
            val ratio = scheme.onSurface.youniversalContrastAgainst(scheme.background)
            assertTrue("$name body text contrast is $ratio", ratio >= 4.5f)
        }
    }

    @Test
    fun everyShippedPaletteHasLegibleButtonLabels() {
        for ((name, scheme) in schemes()) {
            val ratio = scheme.onPrimary.youniversalContrastAgainst(scheme.primary)
            assertTrue("$name button label contrast is $ratio", ratio >= 4.5f)
        }
    }

    @Test
    fun everyShippedPaletteHasLegibleContainerText() {
        for ((name, scheme) in schemes()) {
            val roles = listOf(
                "primary" to (scheme.onPrimaryContainer to scheme.primaryContainer),
                "secondary" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
                "tertiary" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
                "error" to (scheme.onErrorContainer to scheme.errorContainer),
            )
            for ((role, colors) in roles) {
                val ratio = colors.first.youniversalContrastAgainst(colors.second)
                assertTrue("$name $role container contrast is $ratio", ratio >= 4.5f)
            }
        }
    }

    @Test
    fun highContrastRaisesBodyTextContrastToAaa() {
        for ((name, scheme) in schemes()) {
            val isDark = scheme.background.youniversalRelativeLuminance() < 0.2f
            val boosted = scheme.youniversalHighContrast(isDark)
            val before = scheme.onSurface.youniversalContrastAgainst(scheme.background)
            val after = boosted.onSurface.youniversalContrastAgainst(boosted.background)
            assertTrue("$name did not improve: $before -> $after", after >= before)
            assertTrue("$name high contrast should reach 7:1, got $after", after >= 7f)
        }
    }

    @Test
    fun creamKeepsADynamicAccentButUsesPaperNeutrals() {
        val cream = youniversalCreamColorScheme()
        // Any accent-driven scheme stands in for a wallpaper palette here.
        val wallpaper = youniversalDarkColorScheme()
        val blended = wallpaper.withCreamNeutrals(cream)

        assertEquals(cream.background, blended.background)
        assertEquals(cream.surfaceContainerHigh, blended.surfaceContainerHigh)
        assertEquals(cream.outline, blended.outline)
        // Accents survive the swap.
        assertEquals(wallpaper.primary, blended.primary)
        assertEquals(wallpaper.tertiaryContainer, blended.tertiaryContainer)
    }

    @Test
    fun creamIsWarmerAndDarkerThanLight() {
        val light = youniversalLightColorScheme()
        val cream = youniversalCreamColorScheme()
        assertTrue(
            "cream should be darker than light",
            cream.background.youniversalRelativeLuminance() < light.background.youniversalRelativeLuminance(),
        )
        assertTrue(
            "cream should be warmer (more red than blue)",
            cream.background.red > cream.background.blue,
        )
    }

    // ------------------------------------------------------------------ seed palettes

    @Test
    fun generatedPalettesKeepAaContrastForSeveralSeeds() {
        val seeds = listOf(
            Color(0xFF3E63DD),
            Color(0xFF0F9C8E),
            Color(0xFFC2185B),
            Color(0xFF1B7F3B),
            Color(0xFF6A3FB5),
            Color(0xFFB26A00),
        )
        for (seed in seeds) {
            for (isDark in listOf(false, true)) {
                val scheme = YouniversalTonalPalette.colorScheme(seed, isDark)
                val body = scheme.onSurface.youniversalContrastAgainst(scheme.background)
                assertTrue("seed $seed dark=$isDark body contrast is $body", body >= 4.5f)
                val label = scheme.onPrimary.youniversalContrastAgainst(scheme.primary)
                assertTrue("seed $seed dark=$isDark label contrast is $label", label >= 3.0f)
            }
        }
    }

    // ------------------------------------------------------------------ transitions

    @Test
    fun blendingAtTheEndpointsReturnsTheSourceSchemes() {
        val from = youniversalLightColorScheme()
        val to = youniversalDarkColorScheme()
        // lerp rebuilds each role from float channels and Color packs those as half floats, so
        // the round trip is not bit-exact. Compare channels within a tolerance rather than
        // asserting Color equality.
        assertSameColor(from.primary, from.youniversalBlend(to, 0f).primary)
        assertSameColor(to.primary, from.youniversalBlend(to, 1f).primary)
    }

    private fun assertSameColor(expected: Color, actual: Color) {
        assertEquals(expected.red, actual.red, 0.002f)
        assertEquals(expected.green, actual.green, 0.002f)
        assertEquals(expected.blue, actual.blue, 0.002f)
        assertEquals(expected.alpha, actual.alpha, 0.002f)
    }

    @Test
    fun blendingInterpolatesEverySampledRole() {
        val from = youniversalLightColorScheme()
        val to = youniversalDarkColorScheme()
        val mid = from.youniversalBlend(to, 0.5f)

        // Nothing snapped: each sampled role sits between its two endpoints.
        val roles = listOf(
            "primary" to Triple(from.primary, mid.primary, to.primary),
            "surface" to Triple(from.surface, mid.surface, to.surface),
            "outline" to Triple(from.outline, mid.outline, to.outline),
            "errorContainer" to Triple(from.errorContainer, mid.errorContainer, to.errorContainer),
            "tertiaryFixed" to Triple(from.tertiaryFixed, mid.tertiaryFixed, to.tertiaryFixed),
        )
        for ((name, colors) in roles) {
            val low = minOf(colors.first.red, colors.third.red)
            val high = maxOf(colors.first.red, colors.third.red)
            assertTrue(
                "$name did not interpolate: ${colors.second.red} not within [$low, $high]",
                colors.second.red >= low - 0.001f && colors.second.red <= high + 0.001f,
            )
        }

        // Not the arithmetic mean of the channels: Color.lerp does not average sRGB channels
        // directly. On CI the light/dark backgrounds' red channel came out 0.4862745 where the
        // arithmetic mean is 0.5254902 — clearly interpolated, just not linearly in sRGB. So the
        // claim worth asserting is that the result lands in the middle half of the range.
        val low = minOf(from.background.red, to.background.red)
        val high = maxOf(from.background.red, to.background.red)
        val mid45 = (low + high) / 2f
        val distance = kotlin.math.abs(mid.background.red - mid45)
        assertTrue(
            "background red ${mid.background.red} is not near the middle of [$low, $high]",
            distance < (high - low) * 0.25f,
        )
    }
}
