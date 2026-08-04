package com.papersreader.app.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

data class NeuripsHit(val title: String, val pdfUrl: String)

/**
 * Searches proceedings.neurips.cc's own site search by title. Many older NeurIPS/NIPS papers
 * (pre-~2016) were never assigned a DOI at all, so neither Crossref nor Unpaywall (DOI-keyed)
 * can find them — but the paper itself is still freely hosted on the proceedings site, just not
 * discoverable through either of those. Falls back to this only when both have failed.
 */
@Singleton
class NeuripsClient @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    suspend fun searchByTitle(title: String): List<NeuripsHit> = withContext(Dispatchers.IO) {
        runCatching {
            val query = URLEncoder.encode(title.take(200), "UTF-8")
            val request = Request.Builder()
                .url("https://proceedings.neurips.cc/papers/search?q=$query")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                parseResults(body)
            }
        }.onFailure { Timber.w(it, "NeurIPS proceedings search failed for title") }.getOrDefault(emptyList())
    }

    private fun parseResults(html: String): List<NeuripsHit> =
        abstractLinkRegex.findAll(html).mapNotNull { m ->
            // Search results link with a site-relative href ("/paper_files/paper/..."), unlike
            // an already-absolute URL a reference might resolve to elsewhere.
            val href = m.groupValues[1].let { if (it.startsWith("/")) "https://proceedings.neurips.cc$it" else it }
            val resultTitle = unescapeHtmlEntities(m.groupValues[2].trim())
            OpenAccessUrlRewriter.neuripsAbstractToPdf(href)?.let { pdfUrl -> NeuripsHit(resultTitle, pdfUrl) }
        }.toList()

    private fun unescapeHtmlEntities(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

    companion object {
        private val abstractLinkRegex = Regex("href=\"([^\"]*Abstract\\.html)\">([^<]+)")
    }
}
