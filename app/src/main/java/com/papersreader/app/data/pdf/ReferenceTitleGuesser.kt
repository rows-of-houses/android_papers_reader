package com.papersreader.app.data.pdf

/**
 * Best-effort extraction of just the *title* portion from a raw "Authors. Title. Venue, Year."
 * citation string — used both as a search query when Crossref couldn't confidently resolve a
 * cleaner title itself (search engines that expect an actual title, like arXiv's and especially
 * proceedings.neurips.cc's own site search, do far worse against the *whole* noisy citation
 * string than against just the title), and to bold the title in place when a reference is shown
 * in the UI.
 */
object ReferenceTitleGuesser {

    // Splits on ". " but *not* when that period immediately follows a single uppercase letter —
    // an author initial like "K." or "Y." — which would otherwise also look like a sentence
    // boundary and fragment the author list into pieces instead of keeping it as one segment.
    private val sentenceBoundary = Regex("(?<![A-Z])\\.\\s+")

    // Numbered/coded bibliography entries keep their own marker ("12. ", "[12] ", "[FB81] ") as
    // part of the entry text (see BibliographyTextParser/InlineCitationDetector). The dotted-
    // number form's own period is itself a sentence boundary, which would otherwise shift every
    // later segment index by one and make the *author list* look like the title — so it has to
    // be stripped before segmenting, not just left for sentenceBoundary to trip over.
    private val leadingMarker = Regex("^\\s*(\\[[^\\]]{1,12}\\]|\\d{1,3}\\.)\\s*")

    // ACL-style entries ("Alan Akbik, ..., and Roland Vollgraf. 2018. Title. Venue...") have no
    // numbering at all, but the bare year between the author list and the title becomes its own
    // segment under the same splitting rule — detected here so it can be skipped rather than
    // mistaken for the title itself.
    private val bareYear = Regex("^\\(?(19|20)\\d{2}[a-z]?\\)?$")

    /** Falls back to the full [referenceText] if the "Authors. Title. ..." pattern isn't found. */
    fun guessTitle(referenceText: String): String =
        findTitleRange(referenceText)?.let { referenceText.substring(it.first, it.last + 1) } ?: referenceText

    /**
     * Same heuristic as [guessTitle], but returns the title's character range within the
     * original [referenceText] (e.g. to bold it in place) instead of copying it out. `null` when
     * no confident "Authors. Title. ..." split is found — unlike [guessTitle], there's no
     * sensible "whole string" range to fall back to.
     */
    fun findTitleRange(referenceText: String): IntRange? {
        val markerEnd = leadingMarker.find(referenceText)?.let { it.range.last + 1 } ?: 0
        val body = referenceText.substring(markerEnd)

        val boundaries = sentenceBoundary.findAll(body).toList()
        val starts = listOf(0) + boundaries.map { it.range.last + 1 }
        val ends = boundaries.map { it.range.first } + body.length
        val segments = starts.zip(ends)
            .map { (start, end) -> start until end }
            .filter { body.substring(it).isNotBlank() }

        var titleIndex = 1
        val candidateAtOne = segments.getOrNull(1)?.let { body.substring(it).trim() }
        if (candidateAtOne != null && bareYear.matches(candidateAtOne)) {
            titleIndex = 2
        }

        val raw = segments.getOrNull(titleIndex) ?: return null
        val rawText = body.substring(raw)
        val leadingWs = rawText.length - rawText.trimStart().length
        val trailingWs = rawText.length - rawText.trimEnd().length
        val trimmedStart = raw.first + leadingWs
        val trimmedEndExclusive = raw.last + 1 - trailingWs
        if (trimmedStart >= trimmedEndExclusive) return null

        return (markerEnd + trimmedStart)..(markerEnd + trimmedEndExclusive - 1)
    }
}
