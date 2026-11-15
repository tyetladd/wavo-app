// Copyright (c) vladkens | MIT License | https://github.com/vladkens/wavo-app

package io.github.tyetladd.wavo

import android.app.Application
import android.content.Context
import java.io.File

/** What the person set. Every behaviour follows one of these, and the screens show the stored value. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The model that transcribes, by its short name; `null` until one is downloaded. */
    var model: String?
        get() = prefs.getString("model", null)
        set(value) = prefs.edit().putString("model", value).apply()

    /** `cpu`, or `gpu` (Vulkan, experimental on phones). */
    var backend: String
        get() = prefs.getString("backend", "cpu") ?: "cpu"
        set(value) = prefs.edit().putString("backend", value).apply()

    /** The language of the speech as a code, `null` to leave it to the model. */
    var language: String?
        get() = prefs.getString("language", null)
        set(value) = prefs.edit().putString("language", value).apply()

    /** Take hesitation sounds out and write Russian numbers in digits. */
    var clean: Boolean
        get() = prefs.getBoolean("clean", true)
        set(value) = prefs.edit().putBoolean("clean", value).apply()

    /** Minutes without a dictation after which the model leaves the memory. */
    var unloadAfterMinutes: Int
        get() = prefs.getInt("unload_after", 10)
        set(value) = prefs.edit().putInt("unload_after", value).apply()
}

class App : Application() {
    lateinit var settings: Settings
        private set
    lateinit var history: History
        private set

    /** The models' folder: a file for each model downloaded. */
    val models: File get() = File(filesDir, "models")

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        history = History(this)
        // What the first builds' downloader left: nothing reads it now.
        File(filesDir, "hub").deleteRecursively()
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
