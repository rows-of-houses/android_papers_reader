package com.papersreader.app.data.repository

import com.papersreader.app.data.network.OpenAlexClient
import com.papersreader.app.data.network.OpenAlexWork
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A paper that cites the currently open paper, resolved via OpenAlex. [link] is always populated
 * with the best available destination — DOI link, then OpenAlex's own resolved open-access PDF
 * url, then a plain open-access landing page, then finally the OpenAlex work page as a last
 * resort — so tapping a row essentially never dead-ends the way an unresolved reference can.
 */
data class CitingPaper(
    val id: String, // OpenAlex work id (e.g. "W2741809807") — stable key for list items & in-flight download tracking
    val title: String,
    val authorsDisplay: String?,
    val year: Int?,
    val link: String,
    val doi: String?,
    val openAccessPdfUrl: String?,
)

/**
 * [Found] with an empty list means OpenAlex was reached and confidently reports zero citing
 * papers. [Unavailable] means we couldn't establish that either way — the paper's identity
 * couldn't be resolved, or a request failed — kept distinct so the UI never claims "no citations"
 * for a lookup that simply didn't work.
 */
sealed class CitedByOutcome {
    data class Found(val papers: List<CitingPaper>) : CitedByOutcome()
    object Unavailable : CitedByOutcome()
}

@Singleton
class CitedByRepository @Inject constructor(
    private val openAlexClient: OpenAlexClient,
) {
    /** Resolves [paperTitle] to its OpenAlex work id, then fetches everything citing it. */
    suspend fun findCitingPapers(paperTitle: String): CitedByOutcome {
        val workId = openAlexClient.findWorkId(paperTitle) ?: return CitedByOutcome.Unavailable
        val works = openAlexClient.fetchCitingWorks(workId) ?: return CitedByOutcome.Unavailable
        return CitedByOutcome.Found(works.mapNotNull { it.toCitingPaper() })
    }

    private fun OpenAlexWork.toCitingPaper(): CitingPaper? {
        val workTitle = title ?: return null
        val workId = id?.substringAfterLast('/') ?: return null
        val pdfUrl = bestOaLocation?.pdfUrl
        val oaLandingUrl = openAccess?.oaUrl
        val link = doi ?: pdfUrl ?: oaLandingUrl ?: "https://openalex.org/$workId"
        return CitingPaper(
            id = workId,
            title = workTitle,
            authorsDisplay = authorships.mapNotNull { it.author?.displayName }.takeIf { it.isNotEmpty() }?.joinToString(", "),
            year = publicationYear,
            link = link,
            doi = doi?.removePrefix("https://doi.org/"),
            openAccessPdfUrl = pdfUrl,
        )
    }
}
