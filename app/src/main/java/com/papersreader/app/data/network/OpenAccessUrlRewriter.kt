package com.papersreader.app.data.network

/**
 * Rewrites known abstract/landing-page URL patterns to their direct PDF equivalent, so a
 * one-click "download this reference" can skip the extra hop through an HTML page. Pulled out
 * of [com.papersreader.app.data.repository.ReferenceRepository] to keep it unit-testable.
 */
object OpenAccessUrlRewriter {

    private val arxivAbs = Regex("^https?://(?:www\\.)?arxiv\\.org/abs/([\\w.\\-]+?)(?:v\\d+)?/?$", RegexOption.IGNORE_CASE)

    /** e.g. .../paper_files/paper/2010/hash/<id>-Abstract.html — the /file/ sibling is the PDF. */
    private val neuripsAbstract = Regex(
        "^(https?://proceedings\\.neurips\\.cc/(?:paper_files/paper|paper)/\\d{4})/hash/([\\w.\\-]+)-Abstract(?:-[\\w]+)?\\.html$",
        RegexOption.IGNORE_CASE,
    )

    fun toDirectPdfUrl(url: String): String {
        arxivAbs.find(url)?.let { m -> return "https://arxiv.org/pdf/${m.groupValues[1]}" }
        neuripsAbstractToPdf(url)?.let { return it }
        return url
    }

    /** Public separately from [toDirectPdfUrl] since [NeuripsClient] builds these from search-result hrefs, not from an already-resolved reference URL. */
    fun neuripsAbstractToPdf(url: String): String? =
        neuripsAbstract.find(url)?.let { m -> "${m.groupValues[1]}/file/${m.groupValues[2]}-Paper.pdf" }
}
