// Copyright (c) vladkens | MIT License | https://github.com/vladkens/wavo-app

package io.github.tyetladd.wavo

/**
 * The engine, `core/` in Rust: see its `jni.rs` for what each function does. Every function throws
 * a [RuntimeException] with the engine's message when it fails. [load], [transcribe] and [free] are
 * called on one thread, [Engine]'s.
 */
object Native {
    init {
        System.loadLibrary("wavo_android")
    }

    fun interface Progress {
        /** Bytes done and in all; `false` stops the download. */
        fun onProgress(done: Long, total: Long): Boolean
    }

    @JvmStatic external fun models(root: String): String

    @JvmStatic external fun pull(root: String, name: String, progress: Progress): String

    @JvmStatic external fun remove(root: String, name: String): Long

    @JvmStatic external fun load(path: String, backend: String): Long

    @JvmStatic external fun device(handle: Long): String

    @JvmStatic external fun languages(handle: Long): String

    @JvmStatic external fun transcribe(handle: Long, pcm: FloatArray, language: String?, clean: Boolean): String

    @JvmStatic external fun free(handle: Long)
}
