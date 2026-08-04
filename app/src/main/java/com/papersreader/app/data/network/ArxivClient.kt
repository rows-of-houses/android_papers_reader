package com.papersreader.app.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import timber.log.Timber
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.parsers.DocumentBuilderFactory

data class ArxivHit(val title: String, val pdfUrl: String)

/**
 * Searches arXiv's public Atom API by title. Used as a fallback when a reference resolves to a
 * paywalled publisher/DOI landing page (e.g. a conference proceedings entry from Crossref) but
 * the same paper also has an open-access arXiv preprint, which is extremely common in CS — e.g.
 * "A ConvNet for the 2020s" resolves via Crossref to an ACM/IEEE page with no direct PDF, but is
 * also arXiv:2201.03545.
 */
@Singleton
class ArxivClient @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    suspend fun searchByTitle(title: String): List<ArxivHit> = withContext(Dispatchers.IO) {
        runCatching {
            val query = URLEncoder.encode("ti:\"${title.take(200)}\"", "UTF-8")
            val request = Request.Builder()
                .url("https://export.arxiv.org/api/query?search_query=$query&max_results=$MAX_RESULTS")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                parseEntries(body)
            }
        }.onFailure { Timber.w(it, "arXiv search failed for title") }.getOrDefault(emptyList())
    }

    private fun parseEntries(xml: String): List<ArxivHit> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        val entries = doc.getElementsByTagName("entry")
        return (0 until entries.length).mapNotNull { i ->
            val entry = entries.item(i) as Element
            val entryTitle = entry.getElementsByTagName("title").item(0)?.textContent
                ?.trim()?.replace(Regex("\\s+"), " ") ?: return@mapNotNull null
            val id = entry.getElementsByTagName("id").item(0)?.textContent?.trim() ?: return@mapNotNull null
            ArxivHit(title = entryTitle, pdfUrl = OpenAccessUrlRewriter.toDirectPdfUrl(id))
        }
    }

    companion object {
        private const val MAX_RESULTS = 3
    }
}
