package com.zerovox

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedMeterTest {
    @Test
    fun reportsFirstSampleOverTheElapsedWindow() {
        val meter = SpeedMeter(windowMs = 3000)
        meter.reset(0L, 0L)
        assertEquals(1000.0, meter.update(500L, 500L), 0.01)
    }

    @Test
    fun averagesBytesOverTheWindow() {
        val meter = SpeedMeter(windowMs = 3000)
        meter.reset(0L, 0L)
        meter.update(1000L, 1000L)
        assertEquals(1000.0, meter.update(2000L, 2000L), 0.01)
        assertEquals(1000.0, meter.update(3000L, 3000L), 0.01)
        assertEquals(1000.0, meter.update(4000L, 4000L), 0.01)
    }

    @Test
    fun returnsZeroWhenTheWindowHasNoNewBytes() {
        val meter = SpeedMeter(windowMs = 3000)
        meter.reset(0L, 1000L)
        meter.update(1000L, 1000L)
        meter.update(2000L, 1000L)
        meter.update(3000L, 1000L)
        assertEquals(0.0, meter.update(4000L, 1000L), 0.01)
    }

    @Test
    fun ignoresSamplesOlderThanTheWindow() {
        val meter = SpeedMeter(windowMs = 3000)
        meter.reset(0L, 0L)
        meter.update(1000L, 1000L)
        meter.update(2000L, 2000L)
        meter.update(3000L, 3000L)
        // The sample at t=0 is dropped; the remaining window is 1000..4000 ms.
        assertEquals(1000.0, meter.update(4000L, 4000L), 0.01)
    }
}
