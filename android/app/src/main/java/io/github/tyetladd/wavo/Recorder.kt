// Copyright (c) vladkens | MIT License | https://github.com/vladkens/wavo-app

package io.github.tyetladd.wavo

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * The microphone as the engine takes it: 16 kHz mono floats in [-1, 1]. A recording ends by
 * itself at [MAX_SECONDS].
 */
class Recorder {
    private val main = Handler(Looper.getMainLooper())
    private var record: AudioRecord? = null
    private var reader: Thread? = null

    @Volatile private var running = false
    private val chunks = ArrayList<FloatArray>()
    private var samples = 0

    val recording: Boolean get() = running

    /**
     * Starts recording; `false` when the microphone can't be opened. `level` gets the loudest
     * sample of each tenth of a second, and `full` runs when the recording reaches its limit, both
     * on the main thread. The caller has checked the permission.
     */
    @SuppressLint("MissingPermission")
    fun start(level: (Float) -> Unit, full: () -> Unit): Boolean {
        if (running) return true

        val smallest = AudioRecord.getMinBufferSize(RATE, CHANNEL, FORMAT)
        if (smallest <= 0) return false
        // VOICE_RECOGNITION: the raw microphone, without the processing tuned for calls.
        val opened = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, CHANNEL, FORMAT, maxOf(smallest, RATE * 4))
        }.getOrNull()
        if (opened == null || opened.state != AudioRecord.STATE_INITIALIZED) {
            opened?.release()
            return false
        }

        opened.startRecording()
        if (opened.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            opened.release()
            return false
        }

        synchronized(chunks) {
            chunks.clear()
            samples = 0
        }
        record = opened
        running = true
        reader = thread(name = "wavo-mic") {
            val buffer = FloatArray(RATE / 10)
            while (running) {
                val n = opened.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) break

                val chunk = buffer.copyOf(n)
                val total = synchronized(chunks) {
                    chunks.add(chunk)
                    samples += n
                    samples
                }
                val loudest = chunk.maxOf(::abs)
                main.post { if (running) level(loudest) }
                if (total >= RATE * MAX_SECONDS) {
                    main.post { if (running) full() }
                    break
                }
            }
        }
        return true
    }

    /** Ends the recording and returns it. */
    fun stop(): FloatArray {
        running = false
        record?.let { runCatching { it.stop() } }
        reader?.join(1000)
        record?.release()
        record = null
        reader = null

        synchronized(chunks) {
            val out = FloatArray(samples)
            var at = 0
            for (chunk in chunks) {
                chunk.copyInto(out, at)
                at += chunk.size
            }
            chunks.clear()
            samples = 0
            return out
        }
    }

    companion object {
        const val RATE = 16_000
        const val MAX_SECONDS = 600
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val FORMAT = AudioFormat.ENCODING_PCM_FLOAT
    }
}
