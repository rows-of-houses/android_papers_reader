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

data class ResolvedReference(val title: String, val url: String, val doi: String?)

private data class CrossrefCandidate(val title: String, val url: String, val doi: String?, val score: Double)

/**
 * Resolves a raw citation string ("J. Smith et al., Attention is all you need, NeurIPS 2017")
 * to a real article via Crossref's free bibliographic-match endpoint. No API key needed; we
 * score every candidate against the reference text via [ReferenceMatcher] and reject weak
 * matches rather than trusting Crossref's top hit blindly (it always returns *something*, even
 * for an unrelated query).
 */
@Singleton
class CrossrefClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    suspend fun resolve(referenceText: String): ResolvedReference? = withContext(Dispatchers.IO) {
        try {
            val query = URLEncoder.encode(referenceText.take(300), "UTF-8")
            val request = Request.Builder()
                .url("https://api.crossref.org/works?query.bibliographic=$query&rows=$CANDIDATE_ROWS")
                .header("User-Agent", "PapersReaderAndroid/1.0 (mailto:$CONTACT_EMAIL)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val parsed = json.decodeFromString(CrossrefResponse.serializer(), body)

                val best = parsed.message?.items.orEmpty()
                    .mapNotNull { item ->
                        val title = item.title.firstOrNull() ?: return@mapNotNull null
                        val url = item.URL ?: item.DOI?.let { "https://doi.org/$it" } ?: return@mapNotNull null
                        CrossrefCandidate(title, url, item.DOI, ReferenceMatcher.titleOverlapScore(title, referenceText))
                    }
                    .maxByOrNull { it.score } ?: return@withContext null

                if (best.score < ReferenceMatcher.MIN_MATCH_SCORE) return@withContext null
                ResolvedReference(title = best.title, url = best.url, doi = best.doi)
            }
        } catch (e: Exception) {
            Timber.w(e, "Crossref resolution failed for reference")
            null
        }
    }

    companion object {
        private const val CONTACT_EMAIL = "almamatr2141@gmail.com"
        private const val CANDIDATE_ROWS = 3
    }
}
