package com.papersreader.app.data.pdf

import com.papersreader.app.data.repository.NormalizedRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineCitationDetectorTest {

    private fun word(text: String) = PdfWord(text, NormalizedRect(0f, 0f, 0.1f, 0.1f))

    @Test
    fun `detects single bracket citation`() {
        val words = listOf(word("Attention"), word("mechanisms"), word("[12]"), word("have"))
        val citations = InlineCitationDetector.detect(words)
        assertEquals(1, citations.size)
        assertEquals(listOf(12), citations[0].referenceIndices)
    }

    @Test
    fun `detects comma list citation`() {
        val citations = InlineCitationDetector.detect(listOf(word("[3, 7, 9]")))
        assertEquals(listOf(3, 7, 9), citations[0].referenceIndices)
    }

    @Test
    fun `detects range citation`() {
        val citations = InlineCitationDetector.detect(listOf(word("[4-6]")))
        assertEquals(listOf(4, 5, 6), citations[0].referenceIndices)
    }

    @Test
    fun `ignores plain bracketed non-numeric text`() {
        val citations = InlineCitationDetector.detect(listOf(word("[Figure]"), word("[sic]")))
        assertTrue(citations.isEmpty())
    }

    @Test
    fun `ignores unreasonably large ranges`() {
        val citations = InlineCitationDetector.detect(listOf(word("[1-9999]")))
        assertTrue(citations.isEmpty())
    }

    @Test
    fun `still matches when trailing punctuation is glued to the bracket`() {
        // Citations are very often the last token before a sentence's period/comma, and
        // PDFBox's word splitting only breaks on whitespace, so the token arrives as "[12].".
        val citations = InlineCitationDetector.detect(listOf(word("[12].")))
        assertEquals(1, citations.size)
        assertEquals(listOf(12), citations[0].referenceIndices)
    }
}
