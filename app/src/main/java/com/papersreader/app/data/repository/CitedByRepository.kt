package com.papersreader.app.data.repository

import com.papersreader.app.data.network.SemanticScholarClient
import com.papersreader.app.data.network.SemanticScholarPaper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A paper that cites the currently open paper, resolved via Semantic Scholar. [link] is always
 * populated with the best available destination — DOI link, then Semantic Scholar's own
 * pre-resolved open-access PDF URL, then finally the Semantic Scholar paper page as a last
 * resort — so tapping a row essentially never dead-ends the way an unresolved reference can.
 */
data class CitingPaper(
    val id: String, // Semantic Scholar paperId — stable key for list items & in-flight download tracking
    val title: String,
    val authorsDisplay: String?,
    val year: Int?,
    val link: String,
    val doi: String?,
    val openAccessPdfUrl: String?,
)

/**
 * [Found] with an empty list means Semantic Scholar was reached and confidently reports zero
 * citing papers. [Unavailable] means we couldn't establish that either way — the paper's identity
 * couldn't be resolved, or a request failed (commonly the free tier's shared rate limit) — kept
 * distinct so the UI never claims "no citations" for a lookup that simply didn't work.
 */
sealed class CitedByOutcome {
    data class Found(val papers: List<CitingPaper>) : CitedByOutcome()
    object Unavailable : CitedByOutcome()
}

@Singleton
class CitedByRepository @Inject constructor(
    private val semanticScholarClient: SemanticScholarClient,
) {
    /** Resolves [paperTitle] to its Semantic Scholar paperId, then fetches everything citing it. */
    suspend fun findCitingPapers(paperTitle: String): CitedByOutcome {
        val paperId = semanticScholarClient.findPaperId(paperTitle) ?: return CitedByOutcome.Unavailable
        val papers = semanticScholarClient.fetchCitingPapers(paperId) ?: return CitedByOutcome.Unavailable
        return CitedByOutcome.Found(papers.mapNotNull { it.toCitingPaper() })
    }

    private fun SemanticScholarPaper.toCitingPaper(): CitingPaper? {
        val paperTitle = title ?: return null
        val doi = externalIds?.DOI
        val oaUrl = openAccessPdf?.url
        val link = when {
            doi != null -> "https://doi.org/$doi"
            oaUrl != null -> oaUrl
            else -> "https://www.semanticscholar.org/paper/$paperId"
        }
        return CitingPaper(
            id = paperId,
            title = paperTitle,
            authorsDisplay = authors.mapNotNull { it.name }.takeIf { it.isNotEmpty() }?.joinToString(", "),
            year = year,
            link = link,
            doi = doi,
            openAccessPdfUrl = oaUrl,
        )
    }
}
