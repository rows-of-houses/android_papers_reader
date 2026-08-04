package com.papersreader.app.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class UnpaywallResponse(
    val is_oa: Boolean = false,
    val best_oa_location: UnpaywallLocation? = null,
)

@Serializable
private data class UnpaywallLocation(val url_for_pdf: String? = null, val url: String? = null)

/**
 * Unpaywall aggregates open-access copies of a paper (institutional repositories, PMC, publisher
 * hybrid-OA, etc.) keyed by DOI — a broader net than any single publisher/repository, and the
 * standard free service for exactly this "does an OA copy of this DOI exist somewhere" question.
 * No key required, just a contact email per their usage terms.
 */
@Singleton
class UnpaywallClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    suspend fun findOpenAccessPdfUrl(doi: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val encodedDoi = URLEncoder.encode(doi, "UTF-8")
            val request = Request.Builder()
                .url("https://api.unpaywall.org/v2/$encodedDoi?email=$CONTACT_EMAIL")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val parsed = json.decodeFromString(UnpaywallResponse.serializer(), body)
                if (!parsed.is_oa) return@withContext null
                parsed.best_oa_location?.url_for_pdf ?: parsed.best_oa_location?.url
            }
        }.onFailure { Timber.w(it, "Unpaywall lookup failed for DOI $doi") }.getOrNull()
    }

    companion object {
        private const val CONTACT_EMAIL = "almamatr2141@gmail.com"
    }
}
