// Copyright (c) vladkens | MIT License | https://github.com/vladkens/wavo-app

package io.github.tyetladd.wavo

import kotlin.math.log10
import kotlin.math.max

/**
 * The recording pill's geometry and its meter, shared by the Dictate screen and the keyboard so
 * the two draw one picture. Every value is the desktop HUD's (`src/hud.rs`), in points.
 */
object Pill {
    const val WIDTH = 176f
    const val HEIGHT = 36f
    const val PAD = 14f
    const val DOT = 10f
    const val RING = 1.2f
    const val BARS = 16
    const val BAR_WIDTH = 3f
    const val BAR_STEP = 5f
    const val BAR_MIN = 3f
    const val BAR_MAX = 20f
    const val WAVE_LOW = 3f
    const val WAVE_HIGH = 9f
    const val CHIP_W = 24f
    const val CHIP_H = 17f

    /** Where the bars start, and the clock after them. */
    const val BARS_X = PAD + DOT + 10f
    const val TIME_X = BARS_X + BARS * BAR_STEP + 8f

    /** A level (linear RMS) on a dB scale: −60 dBFS and below the shortest bar, −12 the tallest. */
    fun barHeight(level: Float): Float {
        val db = 20f * log10(max(level, 1e-6f))
        return lerp(BAR_MIN, BAR_MAX, ((db + 60f) / 48f).coerceIn(0f, 1f))
    }

    /** The ring before anything is heard: sixteen silences. */
    fun empty(): List<Float> = List(BARS) { 0f }

    /** One more level, the oldest dropped: the newest last, as the desktop draws it. */
    fun push(levels: List<Float>, level: Float): List<Float> = (levels + level).takeLast(BARS)

    fun lerp(a: Float, b: Float, k: Float): Float = a + (b - a) * k
}
