package com.papersreader.app.logging

import android.util.Log
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Persists every log line to a small rotating file so the user can copy a crash/error
 * trace out of the app (Settings > Logs) without needing a computer or logcat.
 */
class FileLogTree(private val logDir: File) : Timber.Tree() {

    private val lock = ReentrantLock()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val currentFile: File
        get() = File(logDir, CURRENT_LOG_NAME)

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < Log.INFO) return
        lock.withLock {
            try {
                if (!logDir.exists()) logDir.mkdirs()
                rotateIfNeeded()
                currentFile.appendText(formatLine(priority, tag, message, t))
            } catch (_: Exception) {
                // Logging must never crash the app it is trying to help debug.
            }
        }
    }

    private fun formatLine(priority: Int, tag: String?, message: String, t: Throwable?): String {
        val level = priorityLabel(priority)
        val timestamp = dateFormat.format(Date())
        val header = "$timestamp $level/${tag ?: "App"}: $message\n"
        return if (t != null) header + Log.getStackTraceString(t) + "\n" else header
    }

    private fun priorityLabel(priority: Int) = when (priority) {
        Log.VERBOSE -> "V"
        Log.DEBUG -> "D"
        Log.INFO -> "I"
        Log.WARN -> "W"
        Log.ERROR -> "E"
        Log.ASSERT -> "A"
        else -> "?"
    }

    private fun rotateIfNeeded() {
        if (currentFile.exists() && currentFile.length() > MAX_FILE_BYTES) {
            val previous = File(logDir, PREVIOUS_LOG_NAME)
            previous.delete()
            currentFile.renameTo(previous)
        }
    }

    fun readAll(): String {
        lock.withLock {
            val previous = File(logDir, PREVIOUS_LOG_NAME)
            val previousText = if (previous.exists()) previous.readText() else ""
            val currentText = if (currentFile.exists()) currentFile.readText() else ""
            return previousText + currentText
        }
    }

    fun clear() {
        lock.withLock {
            File(logDir, PREVIOUS_LOG_NAME).delete()
            currentFile.delete()
        }
    }

    companion object {
        private const val CURRENT_LOG_NAME = "app.log"
        private const val PREVIOUS_LOG_NAME = "app.log.1"
        private const val MAX_FILE_BYTES = 1_000_000L
    }
}
