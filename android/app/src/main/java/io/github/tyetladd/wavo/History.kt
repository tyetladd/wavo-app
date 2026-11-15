// Copyright (c) vladkens | MIT License | https://github.com/vladkens/wavo-app

package io.github.tyetladd.wavo

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow

/** A dictation kept. */
data class Entry(
    val id: Long,
    /** When, in milliseconds since 1970. */
    val at: Long,
    val text: String,
    val model: String,
    val audioMs: Long,
    val tookMs: Long,
)

/** Every dictation with text, the newest first. The text only: recordings aren't kept. */
class History(context: Context) : SQLiteOpenHelper(context, "history.db", null, 1) {
    val entries = MutableStateFlow<List<Entry>>(emptyList())

    init {
        reload()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, " +
                "text TEXT NOT NULL, model TEXT NOT NULL, audio_ms INTEGER NOT NULL, took_ms INTEGER NOT NULL)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, from: Int, to: Int) = Unit

    fun add(heard: Heard) {
        val row = ContentValues().apply {
            put("at", System.currentTimeMillis())
            put("text", heard.text)
            put("model", heard.model)
            put("audio_ms", heard.audioMs)
            put("took_ms", heard.tookMs)
        }
        writableDatabase.insert("history", null, row)
        reload()
    }

    fun delete(id: Long) {
        writableDatabase.delete("history", "id = ?", arrayOf(id.toString()))
        reload()
    }

    fun clear() {
        writableDatabase.delete("history", null, null)
        reload()
    }

    private fun reload() {
        val out = ArrayList<Entry>()
        val columns = arrayOf("id", "at", "text", "model", "audio_ms", "took_ms")
        readableDatabase.query("history", columns, null, null, null, null, "id DESC", "500").use { c ->
            while (c.moveToNext()) {
                out += Entry(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5))
            }
        }
        entries.value = out
    }
}
