package com.papersreader.app.data.pdf

/**
 * Best-effort extraction of just the *title* portion from a raw "Authors. Title. Venue, Year."
 * citation string, for use as a search query when Crossref couldn't confidently resolve a
 * cleaner title itself (e.g. an older paper with no registered DOI). Search engines that expect
 * an actual title (arXiv's, and especially proceedings.neurips.cc's own site search, which
 * returns zero results for anything but a fairly exact title match) do far worse against the
 * *whole* noisy citation string — author names, venue, year, page numbers and all — than against
 * just the title on its own.
 */
object ReferenceTitleGuesser {

    // Splits on ". " but *not* when that period immediately follows a single uppercase letter —
    // an author initial like "K." or "Y." — which would otherwise also look like a sentence
    // boundary and fragment the author list into pieces instead of keeping it as one segment.
    private val sentenceBoundary = Regex("(?<![A-Z])\\.\\s+")

    /** Falls back to the full [referenceText] if the "Authors. Title. ..." pattern isn't found. */
    fun guessTitle(referenceText: String): String {
        val segments = sentenceBoundary.split(referenceText.trim()).map { it.trim() }.filter { it.isNotEmpty() }
        return segments.getOrNull(1) ?: referenceText
    }
}
