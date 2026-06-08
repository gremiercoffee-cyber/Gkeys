package com.gremier.gkeys.diag

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures uncaught exceptions to a file so crashes can be inspected from the
 * Settings screen even without a USB/adb connection.
 */
object CrashLogger {

    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(appContext, thread, throwable)
            } catch (_: Throwable) {
                // Never let crash logging itself crash the process further.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        record(context, throwable, thread.name)
    }

    /** Manually record an exception (for try/catch blocks). */
    fun record(context: Context, throwable: Throwable, threadName: String = Thread.currentThread().name) {
        try {
            val sw = StringWriter()
            PrintWriter(sw).use { throwable.printStackTrace(it) }
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val text = buildString {
                appendLine("Time: $timestamp")
                appendLine("Thread: $threadName")
                appendLine("Message: ${throwable.message}")
                appendLine("---")
                append(sw.toString())
            }
            File(context.applicationContext.filesDir, FILE_NAME).writeText(text)
        } catch (_: Throwable) {
        }
    }

    fun lastCrash(context: Context): String? {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        return if (file.exists()) file.readText().takeIf { it.isNotBlank() } else null
    }

    fun clear(context: Context) {
        File(context.applicationContext.filesDir, FILE_NAME).delete()
    }
}
