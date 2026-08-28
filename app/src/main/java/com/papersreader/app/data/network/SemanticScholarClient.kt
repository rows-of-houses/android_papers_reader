package com.papersreader.app.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps the free, keyless Semantic Scholar Graph API to find papers that cite the currently open
 * paper — the reverse direction of [CrossrefClient], which only resolves this paper's own
 * outgoing references. The free tier's rate limit is shared across every unauthenticated caller
 * worldwide and gets exhausted easily (HTTP 429, no `Retry-After` header) — every call here
 * retries once after a short delay before giving up, since the API's own error message is
 * literally "please wait and try again."
 */
@Singleton
class SemanticScholarClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    /**
     * Finds this paper's own Semantic Scholar paperId by title search, scoring every candidate
     * via [ReferenceMatcher] against [title] — same "don't trust the API's top hit blindly"
     * discipline [CrossrefClient.resolve] applies to Crossref. `null` means we couldn't establish
     * an identity for this paper at all (no confident match, or the request itself failed) —
     * callers should treat that as "unavailable", not as "this paper has no citations".
     */
    suspend fun findPaperId(title: String): String? = withContext(Dispatchers.IO) {
        try {
            val query = URLEncoder.encode(title.take(300), "UTF-8")
            val request = Request.Builder()
                .url("https://api.semanticscholar.org/graph/v1/paper/search?query=$query&fields=title&limit=$SEARCH_LIMIT")
                .build()
            executeWithRetry(request)?.use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val parsed = json.decodeFromString(SemanticScholarSearchResponse.serializer(), body)
                parsed.data
                    .mapNotNull { hit -> hit.title?.let { hit to ReferenceMatcher.titleOverlapScore(it, title) } }
                    .filter { (_, score) -> score >= ReferenceMatcher.MIN_MATCH_SCORE }
                    .maxByOrNull { (_, score) -> score }
                    ?.first?.paperId
            }
        } catch (e: Exception) {
            Timber.w(e, "Semantic Scholar paper search failed for title")
            null
        }
    }

    /**
     * Raw citing-paper records for [paperId]. `null` means the request failed (network error,
     * non-2xx status, still rate-limited after the retry) — distinct from a successful response
     * that legitimately lists zero citing papers, which is an empty list.
     */
    suspend fun fetchCitingPapers(paperId: String): List<SemanticScholarPaper>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(
                    "https://api.semanticscholar.org/graph/v1/paper/$paperId/citations" +
                        "?fields=title,authors,year,externalIds,openAccessPdf&limit=$CITATIONS_LIMIT",
                )
                .build()
            executeWithRetry(request)?.use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                json.decodeFromString(SemanticScholarCitationsResponse.serializer(), body)
                    .data.mapNotNull { it.citingPaper }
            }
        } catch (e: Exception) {
            Timber.w(e, "Semantic Scholar citations fetch failed for paperId $paperId")
            null
        }
    }

    /** One retry after [RETRY_DELAY_MS] on a 429 — everything else is returned as-is (including other error statuses, left for the caller to reject). */
    private suspend fun executeWithRetry(request: Request): Response? {
        val first = runCatching { httpClient.newCall(request).execute() }.getOrNull() ?: return null
        if (first.code != 429) return first
        first.close()
        delay(RETRY_DELAY_MS)
        return runCatching { httpClient.newCall(request).execute() }.getOrNull()
    }

    companion object {
        private const val SEARCH_LIMIT = 3
        private const val CITATIONS_LIMIT = 100
        private const val RETRY_DELAY_MS = 1500L
    }
}
