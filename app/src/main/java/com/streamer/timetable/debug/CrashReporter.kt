package com.streamer.timetable.debug

import android.content.Context
import android.os.Build
import com.streamer.timetable.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Writes uncaught exceptions to a file so a tester can send back something useful.
 *
 * Without this the only report available is "it closed" -- a sideloaded app has no
 * store console behind it, and asking someone to capture logcat is not realistic.
 *
 * Nothing leaves the device on its own. The log is written locally and only shared
 * if the user chooses to, from the debug menu.
 */
object CrashReporter {

    private const val DIRECTORY = "crashes"
    private const val KEEP = 5

    private val STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(appContext, thread, error) }
            // Always hand back to the platform handler. Swallowing it would leave the
            // process alive in a broken state instead of dying cleanly.
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }

        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        val report = buildString {
            appendLine("Streamer ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("When:    ${STAMP.format(Instant.now())}")
            appendLine("Device:  ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Thread:  ${thread.name}")
            appendLine()
            append(trace)
        }

        File(directory, "crash-${System.currentTimeMillis()}.txt").writeText(report)
        prune(directory)
    }

    /** Keeps only the most recent few, so a crash loop cannot fill the device. */
    private fun prune(directory: File) {
        directory.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP)
            ?.forEach { it.delete() }
    }

    fun latest(context: Context): File? =
        File(context.filesDir, DIRECTORY).listFiles()
            ?.maxByOrNull { it.lastModified() }

    fun count(context: Context): Int =
        File(context.filesDir, DIRECTORY).listFiles()?.size ?: 0

    fun clear(context: Context) {
        File(context.filesDir, DIRECTORY).listFiles()?.forEach { it.delete() }
    }
}
