// Copyright (c) vladkens | MIT License | https://github.com/vladkens/wavo-app

package io.github.tyetladd.wavo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class PiecesTest {
    private val rate = Recorder.RATE

    /** A tone of `seconds` at `level`; level 0.001 is a quiet room. */
    private fun tone(seconds: Double, level: Float): FloatArray =
        FloatArray((seconds * rate).toInt()) { i -> level * sin(2 * PI * 220 * i / rate).toFloat() }

    /** Feeds the audio a tenth of a second at a time, as the recorder does, and collects the pieces. */
    private fun run(audio: FloatArray): Pair<List<FloatArray>, FloatArray> {
        val pieces = Pieces()
        val out = ArrayList<FloatArray>()
        for (start in audio.indices step rate / 10) {
            pieces.feed(audio.copyOfRange(start, minOf(start + rate / 10, audio.size)))
            while (true) out += pieces.next() ?: break
        }
        return out to pieces.rest()
    }

    @Test
    fun cutsInThePauseAndLosesNothing() {
        val audio = tone(3.0, 0.3f) + tone(1.0, 0.001f) + tone(2.05, 0.3f)
        val (pieces, rest) = run(audio)

        assertEquals(1, pieces.size)
        // In the pause, from 3 s to 4 s.
        assertTrue(pieces[0].size in (3.1 * rate).toInt()..(3.9 * rate).toInt())
        assertEquals(audio.size, pieces[0].size + rest.size)
        assertEquals(audio.toList(), (pieces[0] + rest).toList())
    }

    @Test
    fun aShortPhraseWaitsForTheStop() {
        val (pieces, rest) = run(tone(1.0, 0.3f) + tone(0.4, 0.001f) + tone(1.0, 0.3f))
        assertTrue(pieces.isEmpty())
        assertEquals((2.4 * rate).toInt(), rest.size)
    }

    @Test
    fun silenceIsDroppedButForItsLastSeconds() {
        val (pieces, rest) = run(tone(6.0, 0.001f))
        assertTrue(pieces.isEmpty())
        assertTrue(rest.size <= (2.1 * rate).toInt())

        // The word after a long silence is whole, with at most the 2 s of silence not dropped yet.
        val (none, word) = run(tone(5.0, 0.001f) + tone(1.0, 0.3f))
        assertTrue(none.isEmpty())
        assertTrue(word.size >= (1.2 * rate).toInt() && word.size <= (3.1 * rate).toInt())
    }

    @Test
    fun speechWithoutAPauseIsCutAtItsQuietest() {
        // Twenty seconds, softer for 0.3 s at 15 s, but never quiet.
        val audio = tone(15.0, 0.3f) + tone(0.3, 0.1f) + tone(4.7, 0.3f)
        val (pieces, rest) = run(audio)

        assertEquals(1, pieces.size)
        assertTrue(pieces[0].size in 15 * rate..(15.3 * rate).toInt())
        assertEquals(audio.size, pieces[0].size + rest.size)
    }

    @Test
    fun everyPauseCuts() {
        val phrase = tone(2.5, 0.2f) + tone(0.8, 0.002f)
        val (pieces, rest) = run(phrase + phrase + phrase + tone(1.0, 0.2f))
        assertEquals(3, pieces.size)
        assertTrue(rest.size > rate && rest.size < 2 * rate)
        assertNull(Pieces().next())
    }
}
