package com.arcmusic

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last few crashes on disk so they can be saved and sent on.
 *
 * There is no analytics and nothing leaves the phone by itself: this only records what
 * the system was about to report anyway, which is the difference between "it keeps
 * stopping" and a file name and line number.
 */
object CrashLog {

    private const val MAX_FILES = 5

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun directory(context: Context): File =
        File(context.applicationContext.filesDir, "logs").apply { mkdirs() }

    fun files(context: Context): List<File> =
        directory(context).listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()

    /** Everything a person would need to diagnose a report, as one string. */
    fun report(context: Context): String = buildString {
        appendLine("A(rc) diagnostics")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Generated: ${stamp()}")
        val list = files(context)
        if (list.isEmpty()) {
            appendLine()
            appendLine("No crashes recorded on this phone.")
        }
        list.forEach { file ->
            appendLine()
            appendLine("--- ${file.name} ---")
            appendLine(runCatching { file.readText() }.getOrDefault("unreadable"))
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val directory = directory(context)
        val text = buildString {
            appendLine("When: ${stamp()}")
            appendLine("Thread: ${thread.name}")
            appendLine(
                "Device: ${Build.MANUFACTURER} ${Build.MODEL}, " +
                    "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
            )
            appendLine()
            appendLine(error.stackTraceToString())
        }
        runCatching {
            File(directory, "crash-${stamp()}.txt").writeText(text)
        }
        // Only the newest handful are useful; older ones just grow the folder.
        directory.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_FILES)
            ?.forEach { runCatching { it.delete() } }
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}
