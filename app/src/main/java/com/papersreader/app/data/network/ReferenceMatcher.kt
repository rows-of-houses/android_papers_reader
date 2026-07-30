package com.papersreader.app.data.network

/**
 * Scores how plausibly a candidate work title matches a raw citation string. Pulled out of
 * [CrossrefClient] so the matching heuristic can be unit tested without a network layer.
 */
object ReferenceMatcher {

    const val MIN_MATCH_SCORE = 0.5

    private val stopWords = setOf(
        "the", "and", "for", "with", "from", "into", "using", "based", "via", "abs",
        "arxiv", "corr", "preprint", "vol", "pages", "proceedings", "conference", "journal",
    )

    /** Fraction of the candidate title's significant words that also appear in the reference text. */
    fun titleOverlapScore(candidateTitle: String, referenceText: String): Double {
        val titleWords = significantWords(candidateTitle)
        if (titleWords.isEmpty()) return 0.0
        val referenceWords = significantWords(referenceText)
        return titleWords.count { it in referenceWords }.toDouble() / titleWords.size
    }

    fun significantWords(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in stopWords }
            .toSet()
}
