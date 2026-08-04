package com.papersreader.app.data.repository

import com.papersreader.app.data.network.ArxivClient
import com.papersreader.app.data.network.CrossrefClient
import com.papersreader.app.data.network.NeuripsClient
import com.papersreader.app.data.network.OpenAccessUrlRewriter
import com.papersreader.app.data.network.ReferenceMatcher
import com.papersreader.app.data.network.UnpaywallClient
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

data class ReferenceTarget(val url: String, val resolvedTitle: String?, val doi: String? = null)

@Singleton
class ReferenceRepository @Inject constructor(
    private val crossrefClient: CrossrefClient,
    private val arxivClient: ArxivClient,
    private val unpaywallClient: UnpaywallClient,
    private val neuripsClient: NeuripsClient,
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
            ReferenceTarget(url = resolved.url, resolvedTitle = resolved.title, doi = resolved.doi)
        } else {
            ReferenceTarget(url = scholarSearchUrl(referenceText), resolvedTitle = null)
        }
    }

    /**
     * Attempts a one-click download of an open-access PDF for [url], trying progressively wider
     * nets so a paywalled Crossref/DOI hit doesn't mean the button just fails:
     * 1. Rewrite known abstract-page patterns (arXiv, NeurIPS proceedings) to their direct PDF
     *    URL and try that directly.
     * 2. [Unpaywall](https://unpaywall.org), keyed by [doi] — aggregates OA copies (institutional
     *    repositories, PMC, hybrid-OA, etc.) from far more sources than any one publisher.
     * 3. Search arXiv by [fallbackTitle] — covers the common case of a paywalled publisher DOI
     *    (e.g. an IEEE/ACM page) whose paper also has an arXiv preprint that neither Crossref nor
     *    Unpaywall happens to link to (e.g. "A ConvNet for the 2020s" / ConvNeXt).
     * 4. Search proceedings.neurips.cc by [fallbackTitle] — many pre-~2016 NeurIPS/NIPS papers
     *    were never assigned a DOI at all, so neither Crossref nor Unpaywall can find them, even
     *    though the paper is freely hosted right there.
     * Every candidate is verified to actually be a PDF response before being kept, so a
     * paywalled/HTML landing page just falls through to the next source instead of saving garbage.
     */
    suspend fun tryDownloadOpenAccessPdf(url: String, doi: String? = null, fallbackTitle: String? = null): ByteArray? =
        withContext(Dispatchers.IO) {
            downloadIfPdf(OpenAccessUrlRewriter.toDirectPdfUrl(url))
                ?: doi?.let { unpaywallClient.findOpenAccessPdfUrl(it) }?.let { downloadIfPdf(OpenAccessUrlRewriter.toDirectPdfUrl(it)) }
                ?: fallbackTitle?.let { title ->
                    arxivClient.searchByTitle(title)
                        .firstOrNull { ReferenceMatcher.titleOverlapScore(it.title, title) >= ReferenceMatcher.MIN_MATCH_SCORE }
                        ?.let { hit -> downloadIfPdf(hit.pdfUrl) }
                }
                ?: fallbackTitle?.let { title ->
                    neuripsClient.searchByTitle(title)
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
