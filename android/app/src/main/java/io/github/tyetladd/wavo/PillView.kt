// Copyright (c) vladkens | MIT License | https://github.com/vladkens/wavo-app

package io.github.tyetladd.wavo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View

/**
 * The recording pill in the voice keyboard, which is a plain View hierarchy rather than Compose.
 * It draws through [Pill.draw] — the same draw the Dictate screen uses, so the two show one
 * picture — and owns its own tick: the core's gradient is asked for only while [Pill.animates]
 * says so, and never at all with the animation setting off.
 */
class PillView(context: Context) : View(context) {
    private val main = Handler(Looper.getMainLooper())
    private var field: Bitmap? = null
    private var rim: Bitmap? = null
    private var levels: List<Float> = Pill.empty()
    private var phase = Dictation.Phase.Idle
    private var language: String? = null
    private var label = "0:00"
    private var phaseStart = 0L
    private var flowStart = 0L
    private var recorded = 0L
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val density = resources.displayMetrics.density
            field = Pill.bitmap(
                Native.gradient(Pill.WIDTH, Pill.HEIGHT, density, SystemClock.elapsedRealtime() - flowStart, false),
                Pill.WIDTH, Pill.HEIGHT, density,
            )
            invalidate()
            main.postDelayed(this, 33)
        }
    }

    /** What the dictation is doing now; called on the main thread as its state changes. */
    fun render(ui: Dictation.Ui, language: String?) {
        val now = SystemClock.elapsedRealtime()
        if (ui.phase != phase) {
            // The flow carries on across the stop; only a new dictation starts it somewhere new.
            if (ui.phase == Dictation.Phase.Recording) flowStart = now
            if (ui.phase == Dictation.Phase.Transcribing) recorded = now - ui.startedAt
            phaseStart = now
            phase = ui.phase
        }
        levels = ui.levels
        this.language = language
        label = clock(if (ui.phase == Dictation.Phase.Transcribing) recorded else now - ui.startedAt)

        val animates = Pill.animates(ui.phase, App.instance.settings.animate)
        visibility = if (animates) VISIBLE else GONE
        when {
            animates && !running -> {
                running = true
                val density = resources.displayMetrics.density
                // The rim does not move: made once, and only the colours are made again.
                rim = Pill.bitmap(
                    Native.gradient(Pill.WIDTH, Pill.HEIGHT, density, 0L, true),
                    Pill.WIDTH, Pill.HEIGHT, density,
                )
                main.post(tick)
            }
            !animates && running -> {
                running = false
                main.removeCallbacks(tick)
                field = null
                rim = null
                invalidate()
            }
            else -> invalidate()
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val density = resources.displayMetrics.density
        setMeasuredDimension((Pill.WIDTH * density).toInt(), (Pill.HEIGHT * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val elapsed = SystemClock.elapsedRealtime() - phaseStart
        val transcribing = phase == Dictation.Phase.Transcribing
        val settle =
            if (transcribing) Pill.ease((elapsed.toFloat() / Pill.SETTLE_MS).coerceIn(0f, 1f)) else 0f
        val density = resources.displayMetrics.density
        canvas.save()
        canvas.scale(density, density)
        Pill.draw(
            canvas, levels, phase, settle, (elapsed - Pill.SETTLE_MS).coerceAtLeast(0L),
            label, language, field, rim,
        )
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        main.removeCallbacks(tick)
    }
}
