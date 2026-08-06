package com.papersreader.app.data.pdf

import com.papersreader.app.data.repository.NormalizedRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineCitationDetectorTest {

    private fun word(text: String, left: Float = 0f, top: Float = 0f, bottom: Float = 0.1f, width: Float = 0.1f) =
        PdfWord(text, NormalizedRect(left, top, left + width, bottom))

    /** Words on the same visual line, left-to-right with a small realistic gap between them. */
    private fun wordsOnLine(vararg texts: String, top: Float = 0.50f, bottom: Float = 0.52f): List<PdfWord> {
        var left = 0.05f
        return texts.map { text ->
            val width = 0.012f * text.length
            word(text, left = left, top = top, bottom = bottom, width = width).also { left += width + 0.01f }
        }
    }

    private fun InlineCitation.numberedIndices(): List<Int> = keys.map { (it as CitationKey.Numbered).index }

    @Test
    fun `detects single bracket citation`() {
        val words = listOf(word("Attention"), word("mechanisms"), word("[12]"), word("have"))
        val citations = InlineCitationDetector.detect(words)
        assertEquals(1, citations.size)
        assertEquals(listOf(12), citations[0].numberedIndices())
    }

    @Test
    fun `detects comma list citation`() {
        val citations = InlineCitationDetector.detect(listOf(word("[3, 7, 9]")))
        assertEquals(listOf(3, 7, 9), citations[0].numberedIndices())
    }

    @Test
    fun `detects range citation`() {
        val citations = InlineCitationDetector.detect(listOf(word("[4-6]")))
        assertEquals(listOf(4, 5, 6), citations[0].numberedIndices())
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
        assertEquals(listOf(12), citations[0].numberedIndices())
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
        assertEquals(listOf(38, 24, 15), citations[0].numberedIndices())
        // The tappable area must span all three glued-together words, not just the first.
        assertEquals(0.16f, citations[0].rects.single().left)
        assertTrue(citations[0].rects.single().right >= 0.29f)
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
        assertEquals(listOf(12), citations[0].numberedIndices())
    }

    @Test
    fun `merges a bracket group that wraps across a real line break in a narrow column`() {
        // Real-world case that broke tapping: "...as face, hand detection [37, 29,\n15, 31, 14,
        // 36]." — a long group in a narrow (two-column) layout wraps its closing bracket onto
        // the line below the opening one.
        val words = listOf(
            word("[37,", left = 0.20f, top = 0.50f, bottom = 0.52f),
            word("29,", left = 0.24f, top = 0.50f, bottom = 0.52f),
            word("15,", left = 0.05f, top = 0.535f, bottom = 0.555f), // wraps to the next line
            word("31,", left = 0.09f, top = 0.535f, bottom = 0.555f),
            word("14,", left = 0.13f, top = 0.535f, bottom = 0.555f),
            word("36].", left = 0.17f, top = 0.535f, bottom = 0.555f),
        )
        val citations = InlineCitationDetector.detect(words)
        assertEquals(1, citations.size)
        assertEquals(listOf(37, 29, 15, 31, 14, 36), citations[0].numberedIndices())
    }

    @Test
    fun `does not merge a bracket group across an unrelated far-away line`() {
        val words = listOf(
            word("[38,", left = 0.20f, top = 0.50f, bottom = 0.52f),
            word("24,", left = 0.20f, top = 0.62f, bottom = 0.64f), // several lines further down
        )
        assertTrue(InlineCitationDetector.detect(words).isEmpty())
    }

    @Test
    fun `detects a single author-year citation`() {
        // "...self-attention layers of the Transformer (Vaswani et al., 2017)." — BERT's actual
        // citation style, which the bracket-only detector never handled at all.
        val words = wordsOnLine("layers", "of", "the", "Transformer", "(Vaswani", "et", "al.,", "2017).")
        val citations = InlineCitationDetector.detect(words)
        assertEquals(1, citations.size)
        assertEquals(listOf(CitationKey.AuthorYear("Vaswani", "2017")), citations[0].keys)
    }

    @Test
    fun `detects a grouped author-year citation with a lettered year suffix`() {
        // "...task-specific features (Peters et al., 2018a), uses..." grouped with a second
        // work in the same parenthetical, semicolon-separated.
        val words = wordsOnLine("features", "(Peters", "et", "al.,", "2018a;", "Radford", "et", "al.,", "2018),", "uses")
        val citations = InlineCitationDetector.detect(words)
        assertEquals(1, citations.size)
        assertEquals(
            listOf(CitationKey.AuthorYear("Peters", "2018a"), CitationKey.AuthorYear("Radford", "2018")),
            citations[0].keys,
        )
    }

    @Test
    fun `detects a grouped author-year citation that wraps across real lines`() {
        // Real-world case that broke tapping: "Unlike recent language representation models
        // (Peters et al., 2018a; Radford et al., 2018), BERT..." — the whole group is longer
        // than fits on one line, so the closing paren ends up on the line below the opening one,
        // same class of bug as the bracket-style line-wrap fix above.
        val line1 = wordsOnLine("models", "(Peters", "et", "al.,", "2018a;", "Radford", top = 0.50f, bottom = 0.52f)
        val line2 = wordsOnLine("et", "al.,", "2018),", "BERT", top = 0.535f, bottom = 0.555f)
        val citations = InlineCitationDetector.detect(line1 + line2)
        assertEquals(1, citations.size)
        assertEquals(
            listOf(CitationKey.AuthorYear("Peters", "2018a"), CitationKey.AuthorYear("Radford", "2018")),
            citations[0].keys,
        )
    }

    @Test
    fun `detects an author-and-author citation`() {
        val words = wordsOnLine("shown", "by", "(Dai", "and", "Le,", "2015)", "that")
        val citations = InlineCitationDetector.detect(words)
        assertEquals(1, citations.size)
        assertEquals(listOf(CitationKey.AuthorYear("Dai", "2015")), citations[0].keys)
    }

    @Test
    fun `does not treat an ordinary parenthetical as a citation`() {
        val words = wordsOnLine("improvement", "(7.7", "point", "absolute", "improvement)", "over")
        assertTrue(InlineCitationDetector.detect(words).none { it.keys.any { k -> k is CitationKey.AuthorYear } })
    }
}

class AuthorYearMatcherTest {

    @Test
    fun `matches a first-name-first bibliography entry`() {
        // Regression test: BERT's actual bibliography ("William B Dolan and Chris Brockett.
        // 2005. Automatically constructing...") starts with the *first* author's given name, not
        // their surname — a plain startsWith(surname) check (the original implementation) never
        // matched this style at all, so every citation in a paper using it silently resolved to
        // "couldn't find this reference" regardless of how well the rest of the pipeline worked.
        val reference = "William B Dolan and Chris Brockett. 2005. Automatically constructing a corpus " +
            "of sentential paraphrases. In IWP2005."
        assertTrue(AuthorYearMatcher.matches(reference, CitationKey.AuthorYear("Dolan", "2005")))
    }

    @Test
    fun `matches a surname-first bibliography entry too`() {
        val reference = "Carlone, L., Kim, A. (2021). Part1 prelude. SLAM Handbook."
        assertTrue(AuthorYearMatcher.matches(reference, CitationKey.AuthorYear("Carlone", "2021")))
    }

    @Test
    fun `falls back to a bare year when the entry has no lettered suffix`() {
        val reference = "Matthew Peters, Mark Neumann, and Luke Zettlemoyer. 2018. Deep contextualized word representations."
        assertTrue(AuthorYearMatcher.matches(reference, CitationKey.AuthorYear("Peters", "2018a")))
    }

    @Test
    fun `does not match a different author with the same year`() {
        val reference = "William B Dolan and Chris Brockett. 2005. Automatically constructing a corpus."
        assertTrue(!AuthorYearMatcher.matches(reference, CitationKey.AuthorYear("Smith", "2005")))
    }
}
