// Copyright (c) vladkens | MIT License | https://github.com/vladkens/wavo-app

package io.github.tyetladd.wavo

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** What a recording said. */
data class Heard(
    val text: String,
    /** The language of the text, when the model says it. */
    val language: String?,
    /** How long the engine took, and how long the recording is. */
    val tookMs: Long,
    val audioMs: Long,
    val model: String,
    /** `CPU (NEON, 4 threads)`. */
    val device: String,
) {
    companion object {
        fun parse(json: String, model: String, device: String): Heard {
            val o = JSONObject(json)
            return Heard(
                text = o.getString("text"),
                language = if (o.isNull("language")) null else o.getString("language"),
                tookMs = o.getLong("took_ms"),
                audioMs = o.getLong("audio_ms"),
                model = model,
                device = device,
            )
        }
    }
}

/**
 * The speech engine: one thread owns the model. It loads the model the settings name when a job
 * needs it, runs the jobs one at a time, and frees the model after the idle time of the settings.
 * The engine's own threads stay on the fast cores, which it arranges on this thread at the load.
 */
object Engine {
    sealed interface State {
        data object Idle : State
        data class Loading(val model: String) : State
        data class Ready(val model: String, val device: String) : State
        data class Failed(val message: String) : State
    }

    val state = MutableStateFlow<State>(State.Idle)

    private val main = Handler(Looper.getMainLooper())

    // 16 MB of stack: the engine's frontends and decoders run on this thread.
    private val worker = ScheduledThreadPoolExecutor(1) { job -> Thread(null, job, "wavo-engine", 16L shl 20) }

    // Touched on the worker only.
    private var handle = 0L
    private var loaded: String? = null
    private var device = ""
    private var unload: ScheduledFuture<*>? = null

    /** The text of 16 kHz mono samples; `done` runs on the main thread. */
    fun transcribe(pcm: FloatArray, done: (Result<Heard>) -> Unit) {
        worker.execute {
            val settings = App.instance.settings
            val heard = runCatching {
                val model = ensure()
                val json = onGpu(loaded?.endsWith("|gpu") == true) {
                    Native.transcribe(handle, pcm, settings.language, settings.clean)
                }
                Heard.parse(json, model, device)
            }
            heard.exceptionOrNull()?.let(::failed)
            later()
            main.post { done(heard) }
        }
    }

    /** Loads the model ahead of the first dictation: the keyboard came up, or the app opened. */
    fun prewarm() {
        worker.execute {
            // Nothing to say while no model is chosen: the screens say it.
            if (App.instance.settings.model != null) runCatching { ensure() }.exceptionOrNull()?.let(::failed)
            later()
        }
    }

    /** Frees the model now: the settings changed, or its file goes. */
    fun unload() {
        worker.execute {
            free()
            state.value = State.Idle
        }
    }

    /** The loaded model's name, after loading the one the settings name if another, or none, is in. */
    private fun ensure(): String {
        val settings = App.instance.settings
        val name = settings.model ?: error("No model is chosen: download one in Wavo")
        val path = Models.path(name) ?: error("The model $name isn't downloaded")
        val backend = settings.backend
        val key = "$path|$backend"
        if (handle != 0L && loaded == key) return name

        free()
        state.value = State.Loading(name)
        handle = onGpu(backend == "gpu") { Native.load(path, backend) }
        loaded = key
        device = Native.device(handle)
        state.value = State.Ready(name, device)
        return name
    }

    /** Runs a call of the engine; one on the GPU is marked, for the next start to see if it crashed the app. */
    private fun <T> onGpu(gpu: Boolean, call: () -> T): T {
        if (!gpu) return call()

        val settings = App.instance.settings
        settings.gpuBusy = true
        try {
            return call()
        } finally {
            settings.gpuBusy = false
        }
    }

    private fun failed(e: Throwable) {
        free()
        state.value = State.Failed(e.message ?: e.toString())
    }

    private fun free() {
        if (handle != 0L) Native.free(handle)
        handle = 0L
        loaded = null
    }

    /** Frees the model once the idle time passes without a job. */
    private fun later() {
        unload?.cancel(false)
        val minutes = App.instance.settings.unloadAfterMinutes.toLong()
        unload = worker.schedule({
            free()
            if (state.value is State.Ready) state.value = State.Idle
        }, minutes, TimeUnit.MINUTES)
    }
}
