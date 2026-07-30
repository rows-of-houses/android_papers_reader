package com.papersreader.app.logging

import java.io.File

/**
 * The tree must be planted in [android.app.Application.onCreate] before Hilt necessarily
 * finishes wiring the dependency graph, so it is created once here and handed out both to
 * Timber directly and to the DI graph (for the Logs screen) rather than built twice.
 */
object FileLogTreeHolder {
    @Volatile private var instance: FileLogTree? = null

    fun tree(logDir: File): FileLogTree =
        instance ?: synchronized(this) {
            instance ?: FileLogTree(logDir).also { instance = it }
        }
}
