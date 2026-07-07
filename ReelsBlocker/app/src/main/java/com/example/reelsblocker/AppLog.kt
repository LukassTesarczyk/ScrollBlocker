package com.example.reelsblocker

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * Writes debug lines both to logcat (for adb users) and to a small file
 * in app-private storage (for everyone else) so they can be viewed from
 * the Debug tab without any computer or Termux.
 *
 * The accessibility service calls d()/w() from onAccessibilityEvent on
 * the main thread, potentially many times per second while scrolling --
 * disk I/O there was blocking the main thread long enough to cause
 * visible overlay stutter and, in the worst case, an ANR (which is what
 * makes Android mark the service as "not working" in Settings). All file
 * I/O now happens on a single background thread.
 */
object AppLog {
    private const val FILE_NAME = "reels_blocker_log.txt"
    private const val MAX_BYTES = 200_000

    private val writer = Executors.newSingleThreadExecutor()

    // Basic de-dupe so a spammy repeated warning doesn't flood the file.
    private var lastMessage: String? = null
    private var lastAt = 0L

    fun d(context: Context, tag: String, message: String) {
        Log.d(tag, message)
        write(context.applicationContext, tag, message)
    }

    fun w(context: Context, tag: String, message: String) {
        Log.w(tag, message)
        write(context.applicationContext, tag, message)
    }

    private fun write(context: Context, tag: String, message: String) {
        val now = System.currentTimeMillis()
        val key = "$tag|$message"
        if (key == lastMessage && now - lastAt < 500) return
        lastMessage = key
        lastAt = now
        try {
            writer.execute {
                try {
                    val file = File(context.filesDir, FILE_NAME)
                    file.appendText("$now [$tag] $message\n")
                    if (file.length() > MAX_BYTES) trim(file)
                } catch (e: Exception) {
                    Log.w(tag, "AppLog write failed: ${e.message}")
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun trim(file: File) {
        try {
            val text = file.readText()
            file.writeText(text.takeLast(MAX_BYTES / 2))
        } catch (_: Exception) {
        }
    }

    fun readAll(context: Context): String {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists() && file.length() > 0) file.readText() else "(zatím žádný záznam)"
        } catch (e: Exception) {
            "(chyba čtení logu: ${e.message})"
        }
    }

    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).writeText("")
        } catch (_: Exception) {
        }
    }
}
