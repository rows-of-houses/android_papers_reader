package com.papersreader.app.data.pdf

import com.papersreader.app.data.repository.NormalizedRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineCitationDetectorTest {

    private fun word(text: String, left: Float = 0f, top: Float = 0f, bottom: Float = 0.1f, width: Float = 0.1f) =
        PdfWord(text, NormalizedRect(left, top, left + width, bottom))

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

    @Test
    fun `merges a comma list split across separate words by whitespace`() {
        // What PDFBox's word splitter actually hands back for "architectures [38, 24, 15]." —
        // three tokens, none of which is a self-contained bracket marker on its own — this is
        // the real-world shape that broke tapping on grouped citations like this one. Widths
        // and gaps are realistic (a small visible gap between words, matching an actual space
        // character), unlike a single fixed box per word.
        val words = listOf(
            word("architectures", left = 0.05f, top = 0.50f, bottom = 0.52f, width = 0.10f),
            word("[38,", left = 0.16f, top = 0.50f, bottom = 0.52f, width = 0.04f),
            word("24,", left = 0.21f, top = 0.50f, bottom = 0.52f, width = 0.03f),
            word("15].", left = 0.25f, top = 0.50f, bottom = 0.52f, width = 0.04f),
        )
        val citations = InlineCitationDetector.detect(words)
        assertEquals(1, citations.size)
        assertEquals(listOf(38, 24, 15), citations[0].referenceIndices)
        // The tappable area must span all three glued-together words, not just the first.
        assertEquals(0.16f, citations[0].word.rect.left)
        assertTrue(citations[0].word.rect.right >= 0.29f)
    }

    @Test
    fun `merges a bracket split with no real space without breaking the match`() {
        // Regression test: PDFBox can hand back "[12]" as two words, "[" and "12]", with *no*
        // actual gap between them (e.g. a font/style change mid-run rather than real
        // whitespace). Naively joining with a space produces "[ 12]", which no longer matches
        // the marker regex (it requires a digit immediately after "["), silently losing the
        // citation entirely.
        val words = listOf(
            word("results", left = 0.05f, top = 0.50f, bottom = 0.52f, width = 0.08f),
            word("[", left = 0.15f, top = 0.50f, bottom = 0.52f, width = 0.01f),
            word("12].", left = 0.16f, top = 0.50f, bottom = 0.52f, width = 0.03f),
        )
        val citations = InlineCitationDetector.detect(words)
        assertEquals(1, citations.size)
        assertEquals(listOf(12), citations[0].referenceIndices)
    }

    @Test
    fun `does not merge a bracket group across a line break`() {
        val words = listOf(
            word("[38,", left = 0.20f, top = 0.50f, bottom = 0.52f),
            word("24,", left = 0.24f, top = 0.55f, bottom = 0.57f), // next line down
        )
        assertTrue(InlineCitationDetector.detect(words).isEmpty())
    }
}
