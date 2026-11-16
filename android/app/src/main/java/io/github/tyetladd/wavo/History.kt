// Copyright (c) vladkens | MIT License | https://github.com/vladkens/wavo-app

package io.github.tyetladd.wavo

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** A dictation kept. */
data class Entry(
    val id: Long,
    /** When, in milliseconds since 1970. */
    val at: Long,
    val text: String,
    val model: String,
    val audioMs: Long,
    val tookMs: Long,
    /** The recording's name in the recordings folder, or `null` when none was kept. */
    val audio: String? = null,
)

/**
 * Every dictation with text, the newest first, and the recording each points at. The newest 500
 * are shown; nothing is pruned, so a row and its recording stay until the person deletes them.
 */
class History(context: Context) : SQLiteOpenHelper(context, "history.db", null, 2) {
    val entries = MutableStateFlow<List<Entry>>(emptyList())

    /** The recordings the rows point at: History deletes them with their rows. */
    val recordings = Recordings(File(context.filesDir, "recordings"))

    init {
        reload()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, " +
                "text TEXT NOT NULL, model TEXT NOT NULL, audio_ms INTEGER NOT NULL, took_ms INTEGER NOT NULL, " +
                "audio TEXT)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, from: Int, to: Int) {
        // Version 1 kept the text only. Its rows stay, with no recording.
        if (from < 2) db.execSQL("ALTER TABLE history ADD COLUMN audio TEXT")
    }

    fun add(heard: Heard, audio: String? = null) {
        val row = ContentValues().apply {
            put("at", System.currentTimeMillis())
            put("text", heard.text)
            put("model", heard.model)
            put("audio_ms", heard.audioMs)
            put("took_ms", heard.tookMs)
            put("audio", audio)
        }
        writableDatabase.insert("history", null, row)
        reload()
    }

    fun delete(id: Long) {
        val audio = audioOf(id)
        writableDatabase.delete("history", "id = ?", arrayOf(id.toString()))
        audio?.let { recordings.delete(it) }
        reload()
    }

    fun clear() {
        val kept = ArrayList<String>()
        readableDatabase.query("history", arrayOf("audio"), null, null, null, null, null).use { c ->
            while (c.moveToNext()) c.getString(0)?.let { kept += it }
        }
        writableDatabase.delete("history", null, null)
        kept.forEach { recordings.delete(it) }
        reload()
    }

    /** The recording a row points at, read before the row goes. */
    private fun audioOf(id: Long): String? =
        readableDatabase.query("history", arrayOf("audio"), "id = ?", arrayOf(id.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun reload() {
        val out = ArrayList<Entry>()
        val columns = arrayOf("id", "at", "text", "model", "audio_ms", "took_ms", "audio")
        readableDatabase.query("history", columns, null, null, null, null, "id DESC", "500").use { c ->
            while (c.moveToNext()) {
                out += Entry(
                    c.getLong(0), c.getLong(1), c.getString(2), c.getString(3),
                    c.getLong(4), c.getLong(5), c.getString(6),
                )
            }
        }
        entries.value = out
    }
}
