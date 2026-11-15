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
 *
 * With the setting "Transcribe while speaking" the recording is cut at its pauses ([Pieces]) and
 * each piece goes to the engine as the person speaks on, so the stop waits for the last piece
 * only. `onPiece` gets each piece's text as it is ready, in order; `onText` the whole dictation
 * at its end. Without the setting there is one piece, the recording.
 */
class Dictation(
    private val context: Context,
    private val onPiece: (String) -> Unit = {},
    private val onText: (Heard) -> Unit = {},
) {
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
        /** The text of the pieces ready so far, of the dictation under way. */
        val partial: String = "",
    )

    val ui = MutableStateFlow(Ui())

    private val recorder = Recorder()

    // The dictation under way. A result of an earlier one, cancelled, has another number.
    private var session = 0
    private var pieces: Pieces? = null
    private var fed = 0
    private val texts = ArrayList<String>()
    private var pending = 0
    private var stoppedAt = 0L
    private var audioMs = 0L
    private var tookMs = 0L
    private var failure: String? = null
    private var sample: Heard? = null

    val allowed: Boolean
        get() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Starts recording; `false` with [Ui.error] set when it can't. */
    fun start(): Boolean {
        if (ui.value.phase != Phase.Idle) return false

        val settings = App.instance.settings
        val why = when {
            !allowed -> "Wavo isn't allowed to use the microphone"
            settings.model == null -> "No model yet: download one in Wavo"
            !recorder.start(level = ::heard, full = ::stop) -> "The microphone is busy or can't be opened"
            else -> null
        }
        if (why != null) {
            ui.update { it.copy(error = why) }
            return false
        }

        session++
        pieces = if (settings.live) Pieces() else null
        fed = 0
        texts.clear()
        pending = 0
        stoppedAt = 0
        tookMs = 0
        failure = null
        sample = null
        ui.value = Ui(Phase.Recording, startedAt = SystemClock.elapsedRealtime(), last = ui.value.last)
        return true
    }

    /** A tenth of a second more is recorded: the pieces complete with it go to the engine. */
    private fun heard(level: Float) {
        ui.update { it.copy(level = level) }
        val pieces = pieces ?: return
        val more = recorder.since(fed)
        fed += more.size
        pieces.feed(more)
        while (true) submit(pieces.next() ?: break)
    }

    private fun submit(pcm: FloatArray) {
        val mine = session
        pending++
        Engine.transcribe(pcm) { result ->
            if (mine != session) return@transcribe

            pending--
            result.onFailure { failure = it.message ?: "The engine failed" }
            result.onSuccess { heard ->
                sample = sample ?: heard
                tookMs += heard.tookMs
                if (heard.text.isNotEmpty()) {
                    texts += heard.text
                    ui.update { it.copy(partial = texts.joinToString(" ")) }
                    onPiece(heard.text)
                }
            }
            if (stoppedAt != 0L && pending == 0) finish()
        }
    }

    /** Ends the recording and transcribes what the engine hasn't had yet. */
    fun stop() {
        if (ui.value.phase != Phase.Recording) return

        val all = recorder.stop()
        audioMs = all.size * 1000L / Recorder.RATE
        val rest = pieces?.let {
            it.feed(all.copyOfRange(minOf(fed, all.size), all.size))
            it.rest()
        } ?: all
        stoppedAt = SystemClock.elapsedRealtime()
        ui.update { it.copy(phase = Phase.Transcribing, level = 0f) }
        submit(rest)
    }

    /** Every piece is back: the dictation's text. */
    private fun finish() {
        val text = texts.joinToString(" ")
        val heard = sample?.copy(
            text = text,
            tookMs = tookMs,
            audioMs = audioMs,
            waitMs = SystemClock.elapsedRealtime() - stoppedAt,
        )
        val error = when {
            failure != null -> failure
            text.isEmpty() -> "No speech heard"
            else -> null
        }
        val kept = heard?.takeIf { text.isNotEmpty() }
        ui.value = Ui(error = error, last = kept ?: ui.value.last)
        session++
        if (kept != null) {
            App.instance.history.add(kept)
            onText(kept)
        }
    }

    /** Drops the recording, and the pieces the engine still has. What `onPiece` got stays given. */
    fun cancel() {
        if (ui.value.phase == Phase.Idle) return

        if (ui.value.phase == Phase.Recording) recorder.stop()
        session++
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
