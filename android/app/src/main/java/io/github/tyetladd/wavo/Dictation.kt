// Copyright (c) vladkens | MIT License | https://github.com/vladkens/wavo-app

package io.github.tyetladd.wavo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * A dictation from the button to the text: record, transcribe, keep in the history, hand the text
 * over. The app's screen has one and the keyboard has one.
 */
class Dictation(private val context: Context, private val onText: (Heard) -> Unit) {
    enum class Phase { Idle, Recording, Transcribing }

    data class Ui(
        val phase: Phase = Phase.Idle,
        /** The loudest sample of the last tenth of a second, 0 to 1. */
        val level: Float = 0f,
        /** When the recording started, on [SystemClock.elapsedRealtime]. */
        val startedAt: Long = 0,
        /** Why the last dictation gave no text. */
        val error: String? = null,
        val last: Heard? = null,
    )

    val ui = MutableStateFlow(Ui())

    private val recorder = Recorder()

    val allowed: Boolean
        get() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Starts recording; `false` with [Ui.error] set when it can't. */
    fun start(): Boolean {
        if (ui.value.phase != Phase.Idle) return false

        val why = when {
            !allowed -> "Wavo isn't allowed to use the microphone"
            App.instance.settings.model == null -> "No model yet: download one in Wavo"
            !recorder.start(level = { l -> ui.update { it.copy(level = l) } }, full = ::stop) ->
                "The microphone is busy or can't be opened"
            else -> null
        }
        if (why != null) {
            ui.update { it.copy(error = why) }
            return false
        }

        ui.value = Ui(Phase.Recording, startedAt = SystemClock.elapsedRealtime(), last = ui.value.last)
        return true
    }

    /** Ends the recording and transcribes it. */
    fun stop() {
        if (ui.value.phase != Phase.Recording) return

        val pcm = recorder.stop()
        ui.update { it.copy(phase = Phase.Transcribing, level = 0f) }
        Engine.transcribe(pcm) { result ->
            val heard = result.getOrNull()
            val error = when {
                heard == null -> result.exceptionOrNull()?.message ?: "The engine failed"
                heard.text.isEmpty() -> "No speech heard"
                else -> null
            }
            ui.value = Ui(error = error, last = heard?.takeIf { it.text.isNotEmpty() } ?: ui.value.last)
            if (heard != null && heard.text.isNotEmpty()) {
                App.instance.history.add(heard)
                onText(heard)
            }
        }
    }

    /** Drops the recording. A transcription under way finishes, into the history. */
    fun cancel() {
        if (ui.value.phase != Phase.Recording) return

        recorder.stop()
        ui.update { Ui(last = it.last) }
    }

    fun toggle() {
        when (ui.value.phase) {
            Phase.Idle -> start()
            Phase.Recording -> stop()
            Phase.Transcribing -> Unit
        }
    }
}

/** `0:07`. */
fun clock(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

/**
 * What goes before a dictated text in a field: a space when the text before the cursor ends in a
 * word or a mark, nothing at the field's start or after whitespace or an opening bracket or quote.
 */
fun lead(before: CharSequence?): String {
    val last = before?.lastOrNull() ?: return ""
    return if (last.isWhitespace() || last in "([{«“\"'") "" else " "
}
