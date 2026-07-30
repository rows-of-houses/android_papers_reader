package com.papersreader.app.util

object TitleSanitizer {
    private val illegalChars = Regex("[\\\\/:*?\"<>|\\n\\r\\t]")

    /** Turns an arbitrary paper title into a safe, reasonably short file name (without extension). */
    fun toFileNameStem(title: String): String {
        val cleaned = title
            .replace(illegalChars, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val stem = cleaned.ifBlank { "untitled" }
        return if (stem.length > 120) stem.take(120).trim() else stem
    }

    /** Appends " (2)", " (3)", ... until [exists] returns false for the candidate file name. */
    fun dedupe(stem: String, extension: String, exists: (String) -> Boolean): String {
        var candidate = "$stem.$extension"
        var n = 2
        while (exists(candidate)) {
            candidate = "$stem ($n).$extension"
            n++
        }
        return candidate
    }
}
