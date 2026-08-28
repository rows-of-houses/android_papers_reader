package com.papersreader.app.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps the free, keyless OpenAlex API to find papers that cite the currently open paper — the
 * reverse direction of [CrossrefClient], which only resolves this paper's own outgoing
 * references. Replaced an earlier Semantic Scholar-based client: Semantic Scholar's unauthenticated
 * tier shares one global rate-limit pool across every caller worldwide, and in practice that pool
 * is essentially always exhausted, so lookups failed almost every time regardless of retries.
 * OpenAlex instead grants every caller supplying a contact email (the "polite pool", see
 * [CONTACT_EMAIL]) its own generous 100k-request/day, 100-request/second allowance.
 */
@Singleton
class OpenAlexClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    /**
     * Finds this paper's own OpenAlex work id by title search, scoring every candidate via
     * [ReferenceMatcher] against [title] — same "don't trust the API's top hit blindly" discipline
     * [CrossrefClient.resolve] applies to Crossref. `null` means we couldn't establish an identity
     * for this paper at all (no confident match, or the request itself failed) — callers should
     * treat that as "unavailable", not as "this paper has no citations".
     */
    suspend fun findWorkId(title: String): String? = withContext(Dispatchers.IO) {
        try {
            val query = URLEncoder.encode(title.take(300), "UTF-8")
            val request = Request.Builder()
                .url(
                    "https://api.openalex.org/works?search=$query&per_page=$SEARCH_LIMIT" +
                        "&select=id,title&mailto=$CONTACT_EMAIL",
                )
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val parsed = json.decodeFromString(OpenAlexWorksResponse.serializer(), body)
                parsed.results
                    .mapNotNull { hit -> hit.title?.let { hit to ReferenceMatcher.titleOverlapScore(it, title) } }
                    .filter { (_, score) -> score >= ReferenceMatcher.MIN_MATCH_SCORE }
                    .maxByOrNull { (_, score) -> score }
                    ?.first?.id?.substringAfterLast('/')
            }
        } catch (e: Exception) {
            Timber.w(e, "OpenAlex work search failed for title")
            null
        }
    }

    /**
     * Raw citing-work records for [workId]. `null` means the request failed (network error,
     * non-2xx status) — distinct from a successful response that legitimately lists zero citing
     * works, which is an empty list.
     */
    suspend fun fetchCitingWorks(workId: String): List<OpenAlexWork>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(
                    "https://api.openalex.org/works?filter=cites:$workId&per_page=$CITATIONS_LIMIT" +
                        "&select=id,title,doi,publication_year,authorships,open_access,best_oa_location" +
                        "&mailto=$CONTACT_EMAIL",
                )
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                json.decodeFromString(OpenAlexWorksResponse.serializer(), body).results
            }
        } catch (e: Exception) {
            Timber.w(e, "OpenAlex citations fetch failed for work id $workId")
            null
        }
    }

    companion object {
        private const val CONTACT_EMAIL = "almamatr2141@gmail.com"
        private const val SEARCH_LIMIT = 3
        private const val CITATIONS_LIMIT = 100
    }
}
