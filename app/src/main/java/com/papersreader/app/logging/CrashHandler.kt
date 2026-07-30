package com.papersreader.app.logging

import android.os.Build
import timber.log.Timber
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chains onto the platform's default uncaught-exception handler: we record the trace to
 * [lastCrashFile] first (so the user has something to copy even if the process dies before
 * any other log gets flushed), then hand off so the OS still shows its normal crash dialog.
 */
class CrashHandler(private val logDir: File) : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    private val lastCrashFile: File
        get() = File(logDir, LAST_CRASH_NAME)

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            if (!logDir.exists()) logDir.mkdirs()
            lastCrashFile.writeText(buildReport(thread, throwable))
            Timber.tag("Crash").e(throwable, "Uncaught exception on thread ${thread.name}")
        } catch (_: Exception) {
            // Best-effort only; never let logging prevent the real crash handler from running.
        }
        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun buildReport(thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        return buildString {
            appendLine("Papers Reader crash report")
            appendLine("Time: $timestamp")
            appendLine("Thread: ${thread.name}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            append(sw.toString())
        }
    }

    fun readLastCrash(): String? = lastCrashFile.takeIf { it.exists() }?.readText()

    companion object {
        private const val LAST_CRASH_NAME = "last_crash.log"
    }
}
