package com.papersreader.app.data.pdf

/**
 * Pure text-processing half of [ReferenceParser], split out so it can be unit tested without a
 * PDFBox `PDDocument` (no Android runtime needed).
 */
object BibliographyTextParser {

    // IEEE-style templates (IEEEtran.cls) render section headings in letter-spaced small caps,
    // which PDF text extraction turns into e.g. "R EFERENCES" — a literal space after the drop
    // cap. We compare on letters only so that artifact (and stray page numbers/punctuation on
    // the same line) doesn't defeat the match.
    private val headingWords = setOf("REFERENCES", "BIBLIOGRAPHY", "WORKSCITED", "LITERATURECITED")
    private val numberedEntryMarkerRegex = Regex("(?m)^\\s*(\\[\\d{1,3}\\]|\\d{1,3}\\.)\\s+")
    // Author-year style entries ("Carlone, L., Kim, A. ... (2021)."): each entry starts at the
    // left margin with "Surname, " and, unlike numbered styles, has no leading digit/bracket.
    private val authorYearEntryMarkerRegex = Regex("(?m)^\\p{Lu}[\\p{L}\\-']+,\\s")

    fun parse(fullText: String): List<ParsedReference> {
        val bibliographyText = sectionAfterHeading(fullText) ?: return emptyList()
        return splitEntries(bibliographyText)
    }

    fun sectionAfterHeading(text: String): String? {
        val lines = text.lines()
        val headingIndex = lines.indexOfFirst { isHeadingLine(it) }
        if (headingIndex == -1) return null
        return lines.subList(headingIndex + 1, lines.size).joinToString("\n")
    }

    fun isHeadingLine(line: String): Boolean {
        val lettersOnly = line.filter { it.isLetter() }.uppercase()
        return lettersOnly in headingWords
    }

    fun splitEntries(bibliographyText: String): List<ParsedReference> {
        splitOnMarkers(bibliographyText, numberedEntryMarkerRegex)?.let { return it }
        splitOnMarkers(bibliographyText, authorYearEntryMarkerRegex)?.let { return it }
        return emptyList()
    }

    /** Returns null (rather than an empty/single-entry list) if this marker style doesn't apply. */
    private fun splitOnMarkers(bibliographyText: String, markerRegex: Regex): List<ParsedReference>? {
        val markers = markerRegex.findAll(bibliographyText).toList()
        if (markers.size < 2) return null

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
        return entries.ifEmpty { null }
    }
}
