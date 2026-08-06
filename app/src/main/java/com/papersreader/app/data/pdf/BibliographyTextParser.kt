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

    // ACL Anthology-style entries ("Alan Akbik, Duncan Blythe, and Roland Vollgraf. 2018. Title.
    // Venue..." — first name first, no comma after the first author, entries run together with
    // no numbering or blank lines at all). Unlike the surname-first style above, there's no
    // marker sitting right at the left margin to anchor on — the only reliable, recurring shape
    // is the author list *itself*: one or more capitalized names, comma-separated, ending in
    // "and <Name>. <year>." A name word excludes ALL-CAPS tokens (`(?!\p{Lu}{2,}\b)`) so venue
    // abbreviations like "NIST" or "ACL" — which read exactly like a "name" otherwise — don't
    // get swept into the *previous* entry's author list.
    private val aclNameWord = "(?!\\p{Lu}{2,}\\b)\\p{Lu}[\\p{L}'\\-]*"
    private val aclName = "$aclNameWord(?:\\s+$aclNameWord)*"
    private val aclEntryMarkerRegex = Regex(
        "$aclName(?:,\\s+$aclName)*,?\\s+and\\s+$aclName\\.\\s+(19|20)\\d{2}[a-z]?\\.\\s+",
    )

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
        val candidates = listOf(
            numberedEntryMarkerRegex.findAll(bibliographyText).toList(),
            authorYearEntryMarkerRegex.findAll(bibliographyText).toList(),
            aclEntryMarkerRegex.findAll(bibliographyText).toList(),
        )
        // Everything *after* the heading is searched, not just the bibliography itself, because
        // there's no reliable way to detect where it ends in plain extracted text — so a later,
        // unrelated numbered list (e.g. a "1. Question: ... 2. Question: ..." FAQ-style appendix,
        // observed for real in BERT's paper) can rack up 2+ matches for the numbered style even
        // though the actual bibliography right after the heading is author-year with no numbers
        // at all. Whichever style's *first* match starts earliest in the text is the one actually
        // describing this section — a real bibliography's first entry begins right after the
        // heading, while a confounding list from a later section starts much further in.
        val markers = candidates.filter { it.size >= 2 }.minByOrNull { it.first().range.first } ?: emptyList()
        if (markers.size < 2) return emptyList()

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
