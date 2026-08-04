package com.papersreader.app.data.repository

import com.papersreader.app.data.network.ArxivClient
import com.papersreader.app.data.network.CrossrefClient
import com.papersreader.app.data.network.OpenAccessUrlRewriter
import com.papersreader.app.data.network.ReferenceMatcher
import com.papersreader.app.data.pdf.ParsedReference
import com.papersreader.app.data.pdf.ReferenceParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

data class ReferenceTarget(val url: String, val resolvedTitle: String?)

@Singleton
class ReferenceRepository @Inject constructor(
    private val crossrefClient: CrossrefClient,
    private val arxivClient: ArxivClient,
    private val httpClient: OkHttpClient,
) {
    fun parseReferences(file: File): List<ParsedReference> = ReferenceParser.parse(file)

    /**
     * Tries to resolve the reference to its real article via Crossref first; if nothing
     * confident comes back, falls back to a Google Scholar search — the same thing the
     * Chrome extension does, opened in the in-app browser tab for the user to pick from.
     */
    suspend fun resolveTarget(referenceText: String): ReferenceTarget {
        val resolved = crossrefClient.resolve(referenceText)
        return if (resolved != null) {
            ReferenceTarget(url = resolved.url, resolvedTitle = resolved.title)
        } else {
            ReferenceTarget(url = scholarSearchUrl(referenceText), resolvedTitle = null)
        }
    }

    /**
     * Attempts a one-click download of an open-access PDF for [url]: rewrites known abstract-page
     * patterns (currently just arXiv) to their direct PDF URL, then does a real GET and only
     * keeps the bytes if the server actually answered with a PDF — paywalled/HTML landing pages
     * correctly return null here rather than saving garbage into the library.
     *
     * When that direct attempt fails and [fallbackTitle] is given, also searches arXiv by title —
     * many CS references resolve (via Crossref) to a paywalled publisher/DOI page even though the
     * same paper has an open-access arXiv preprint (e.g. "A ConvNet for the 2020s" / ConvNeXt).
     */
    suspend fun tryDownloadOpenAccessPdf(url: String, fallbackTitle: String? = null): ByteArray? =
        withContext(Dispatchers.IO) {
            downloadIfPdf(OpenAccessUrlRewriter.toDirectPdfUrl(url))
                ?: fallbackTitle?.let { title ->
                    arxivClient.searchByTitle(title)
                        .firstOrNull { ReferenceMatcher.titleOverlapScore(it.title, title) >= ReferenceMatcher.MIN_MATCH_SCORE }
                        ?.let { hit -> downloadIfPdf(hit.pdfUrl) }
                }
        }

    private suspend fun downloadIfPdf(candidate: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(candidate)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val contentType = response.header("Content-Type").orEmpty()
                val looksLikePdf = contentType.contains("application/pdf", ignoreCase = true) ||
                    candidate.substringBefore('?').endsWith(".pdf", ignoreCase = true)
                if (!looksLikePdf) return@withContext null
                response.body?.bytes()
            }
        }.onFailure { Timber.w(it, "Open-access download failed for $candidate") }.getOrNull()
    }

    private fun scholarSearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query.take(300), "UTF-8")
        return "https://scholar.google.com/scholar?q=$encoded"
    }
}
