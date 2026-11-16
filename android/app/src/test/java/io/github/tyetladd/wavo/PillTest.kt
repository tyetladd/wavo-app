// Copyright (c) vladkens | MIT License | https://github.com/vladkens/wavo-app

package io.github.tyetladd.wavo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** The pill's meter: the desktop's dB mapping, the ring of levels it draws, and the RMS it takes. */
class PillTest {
    @Test
    fun silence_is_the_shortest_bar_and_not_a_nan() {
        val height = Pill.barHeight(0f)
        assertTrue("silence must be finite, was $height", height.isFinite())
        assertEquals(Pill.BAR_MIN, height, 1e-4f)
    }

    @Test
    fun full_scale_is_the_tallest_bar() {
        assertEquals(Pill.BAR_MAX, Pill.barHeight(1f), 1e-4f)
    }

    @Test
    fun minus_sixty_db_is_the_shortest_bar() {
        assertEquals(Pill.BAR_MIN, Pill.barHeight(0.001f), 1e-3f)
    }

    @Test
    fun minus_twelve_db_is_the_tallest_bar() {
        // 20*log10(0.25) is -12.04 dB, a hair under the top of the scale.
        assertTrue(Pill.barHeight(0.25f) > 19.9f)
    }

    @Test
    fun the_mapping_rises_with_the_level() {
        var last = 0f
        for (step in 1..1000) {
            val level = step / 1000f
            val height = Pill.barHeight(level)
            assertTrue("fell at $level: $height after $last", height >= last)
            last = height
        }
    }

    @Test
    fun the_ring_keeps_the_newest_sixteen() {
        var levels = Pill.empty()
        assertEquals(Pill.BARS, levels.size)
        for (i in 1..17) levels = Pill.push(levels, i.toFloat())
        assertEquals(Pill.BARS, levels.size)
        assertEquals(2f, levels.first(), 0f)
        assertEquals(17f, levels.last(), 0f)
    }

    @Test
    fun rms_is_the_root_mean_square_not_the_peak() {
        val loud = FloatArray(480) { 0.5f }
        assertEquals(0.5f, rms(loud, 0, loud.size), 1e-5f)

        // A square wave of amplitude 1 has RMS 1, where its peak is also 1; a sine's RMS is 1/sqrt(2).
        val sine = FloatArray(4800) { kotlin.math.sin(2.0 * Math.PI * it / 48.0).toFloat() }
        assertEquals(1f / sqrt(2f), rms(sine, 0, sine.size), 1e-3f)
    }

    @Test
    fun rms_of_a_part_is_that_part_alone() {
        val pcm = FloatArray(480) { if (it < 160) 0f else 1f }
        assertEquals(0f, rms(pcm, 0, 160), 1e-6f)
        assertEquals(1f, rms(pcm, 160, 480), 1e-6f)
    }

    @Test
    fun rms_of_nothing_is_silence() {
        assertEquals(0f, rms(FloatArray(0), 0, 0), 0f)
        assertTrue(abs(rms(FloatArray(4), 2, 2)) < 1e-6f)
    }
}
