package com.papersreader.app.data.network

/**
 * Rewrites known abstract/landing-page URL patterns to their direct PDF equivalent, so a
 * one-click "download this reference" can skip the extra hop through an HTML page. Pulled out
 * of [com.papersreader.app.data.repository.ReferenceRepository] to keep it unit-testable.
 */
object OpenAccessUrlRewriter {

    private val arxivAbs = Regex("^https?://(?:www\\.)?arxiv\\.org/abs/([\\w.\\-]+?)(?:v\\d+)?/?$", RegexOption.IGNORE_CASE)

    fun toDirectPdfUrl(url: String): String {
        arxivAbs.find(url)?.let { m -> return "https://arxiv.org/pdf/${m.groupValues[1]}" }
        return url
    }
}
