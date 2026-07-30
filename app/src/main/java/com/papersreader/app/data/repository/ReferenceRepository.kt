package com.papersreader.app.data.repository

import com.papersreader.app.data.network.CrossrefClient
import com.papersreader.app.data.pdf.ParsedReference
import com.papersreader.app.data.pdf.ReferenceParser
import java.io.File
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

data class ReferenceTarget(val url: String, val resolvedTitle: String?)

@Singleton
class ReferenceRepository @Inject constructor(
    private val crossrefClient: CrossrefClient,
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

    private fun scholarSearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query.take(300), "UTF-8")
        return "https://scholar.google.com/scholar?q=$encoded"
    }
}
