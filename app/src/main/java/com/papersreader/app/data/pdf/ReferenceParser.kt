package com.papersreader.app.data.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import timber.log.Timber
import java.io.File

data class ParsedReference(val index: Int, val text: String)

/**
 * Finds the bibliography section of a paper and splits it into individual reference strings.
 * There is no reliable structured format across publishers, so this is heuristic: locate a
 * "References"/"Bibliography" heading, then split what follows on numbered-entry markers
 * ("[12]" or "12.") at the start of a line.
 */
object ReferenceParser {

    private val headingRegex = Regex("^(references|bibliography|works cited)\\s*$", RegexOption.IGNORE_CASE)
    private val entryMarkerRegex = Regex("(?m)^\\s*(\\[\\d{1,3}\\]|\\d{1,3}\\.)\\s+")

    fun parse(file: File): List<ParsedReference> {
        return try {
            PDDocument.load(file).use { doc -> parse(doc) }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse references from ${file.name}")
            emptyList()
        }
    }

    private fun parse(doc: PDDocument): List<ParsedReference> {
        val stripper = PDFTextStripper()
        stripper.sortByPosition = true
        val fullText = stripper.getText(doc)
        val bibliographyText = sectionAfterHeading(fullText) ?: return emptyList()
        return splitEntries(bibliographyText)
    }

    private fun sectionAfterHeading(text: String): String? {
        val lines = text.lines()
        val headingIndex = lines.indexOfFirst { headingRegex.matches(it.trim()) }
        if (headingIndex == -1) return null
        return lines.subList(headingIndex + 1, lines.size).joinToString("\n")
    }

    private fun splitEntries(bibliographyText: String): List<ParsedReference> {
        val markers = entryMarkerRegex.findAll(bibliographyText).toList()
        if (markers.isEmpty()) return emptyList()

        val entries = mutableListOf<ParsedReference>()
        for (i in markers.indices) {
            val start = markers[i].range.first
            val end = if (i + 1 < markers.size) markers[i + 1].range.first else bibliographyText.length
            val raw = bibliographyText.substring(start, end)
                .replace(Regex("\\s+"), " ")
                .trim()
            if (raw.length > 15) {
                entries.add(ParsedReference(index = entries.size + 1, text = raw))
            }
        }
        return entries
    }
}
