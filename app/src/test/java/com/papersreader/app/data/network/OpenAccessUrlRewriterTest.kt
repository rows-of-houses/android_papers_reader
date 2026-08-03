package com.papersreader.app.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAccessUrlRewriterTest {

    @Test
    fun `rewrites arxiv abstract page to direct pdf`() {
        assertEquals(
            "https://arxiv.org/pdf/1706.03762",
            OpenAccessUrlRewriter.toDirectPdfUrl("https://arxiv.org/abs/1706.03762"),
        )
    }

    @Test
    fun `strips version suffix from arxiv id`() {
        assertEquals(
            "https://arxiv.org/pdf/2606.06312",
            OpenAccessUrlRewriter.toDirectPdfUrl("https://arxiv.org/abs/2606.06312v1"),
        )
    }

    @Test
    fun `leaves already-direct pdf urls untouched`() {
        val url = "https://arxiv.org/pdf/1706.03762"
        assertEquals(url, OpenAccessUrlRewriter.toDirectPdfUrl(url))
    }

    @Test
    fun `leaves unrelated urls untouched`() {
        val url = "https://doi.org/10.1000/xyz123"
        assertEquals(url, OpenAccessUrlRewriter.toDirectPdfUrl(url))
    }
}
