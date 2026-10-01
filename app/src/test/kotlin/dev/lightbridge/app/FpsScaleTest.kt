// SPDX-License-Identifier: MIT
package dev.lightbridge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FpsScaleTest {

    @Test
    fun endpointsAreTheSlowestAndFastestRates() {
        assertEquals(1, FpsScale.positionToTenths(0f))
        assertEquals(200, FpsScale.positionToTenths(1f))
        // 0.1 fps is one code every ten seconds; 20 fps is one every 50 ms.
        assertEquals(10_000L, FpsScale.framePeriodMillis(1))
        assertEquals(50L, FpsScale.framePeriodMillis(200))
        assertEquals(0.1, 1_000.0 / FpsScale.framePeriodMillis(1), 0.0001)
        assertEquals(20.0, 1_000.0 / FpsScale.framePeriodMillis(200), 0.0001)
    }

    @Test
    fun positionIsClampedAndMonotonic() {
        assertEquals(1, FpsScale.positionToTenths(-5f))
        assertEquals(200, FpsScale.positionToTenths(9f))
        var previous = 0
        for (step in 0..1000) {
            val tenths = FpsScale.positionToTenths(step / 1000f)
            assertTrue("tenths went backwards at $step", tenths >= previous)
            previous = tenths
        }
    }

    /** Every tenth below 1 fps must be individually reachable — that was the whole request. */
    @Test
    fun everyTenthBelowOneFpsIsReachable() {
        val reachable = (0..1000).map { FpsScale.positionToTenths(it / 1000f) }.toSet()
        for (tenths in 1..10) {
            assertTrue("$tenths tenth(s) is not reachable from the slider", tenths in reachable)
        }
    }

    @Test
    fun slowRatesTakeMostOfTheTrackSoTheyAreSelectable() {
        // Half the slider covers the slow half of the range up to 5 fps.
        assertEquals(51, FpsScale.positionToTenths(0.5f))
        // A quarter of the way in is still a very slow rate, not 5 fps.
        assertTrue(FpsScale.positionToTenths(0.25f) <= 15)
    }

    @Test
    fun positionRoundTripsWithinHalfATenth() {
        for (tenths in FpsScale.MIN_TENTHS..FpsScale.MAX_TENTHS) {
            val position = FpsScale.tenthsToPosition(tenths)
            val back = FpsScale.positionToTenths(position)
            assertTrue("$tenths -> $position -> $back", kotlin.math.abs(back - tenths) <= 1)
        }
    }

    @Test
    fun labelsReadAsTenths() {
        assertEquals("0.1 fps", FpsScale.label(1))
        assertEquals("1.0 fps", FpsScale.label(10))
        assertEquals("8.0 fps", FpsScale.label(80))
        assertEquals("19.9 fps", FpsScale.label(199))
        assertEquals("20.0 fps", FpsScale.label(200))
    }

    @Test
    fun framePeriodsMatchTheRate() {
        assertEquals(10_000L, FpsScale.framePeriodMillis(1))   // 0.1 fps
        assertEquals(5_000L, FpsScale.framePeriodMillis(2))    // 0.2 fps
        assertEquals(1_250L, FpsScale.framePeriodMillis(8))    // 0.8 fps
        assertEquals(1_000L, FpsScale.framePeriodMillis(10))   // 1.0 fps
        assertEquals(125L, FpsScale.framePeriodMillis(80))     // 8.0 fps
        assertEquals(100L, FpsScale.framePeriodMillis(100))    // 10.0 fps
        assertEquals(50L, FpsScale.framePeriodMillis(200))     // 20.0 fps
    }

    @Test
    fun verySlowRatesAreFlaggedForAWarning() {
        assertTrue(FpsScale.isVerySlow(1))
        assertTrue(FpsScale.isVerySlow(10))
        assertFalse(FpsScale.isVerySlow(11))
        assertFalse(FpsScale.isVerySlow(80))
    }

    @Test
    fun oldWholeFpsPreferencesMigrate() {
        assertEquals(80, FpsScale.fromWholeFps(8))
        assertEquals(20, FpsScale.fromWholeFps(2))
        assertEquals(200, FpsScale.fromWholeFps(20))
        assertEquals(200, FpsScale.fromWholeFps(99))
        assertEquals(10, FpsScale.fromWholeFps(0))
    }
}
