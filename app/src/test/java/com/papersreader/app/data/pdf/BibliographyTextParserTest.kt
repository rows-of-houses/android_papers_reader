package com.papersreader.app.data.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BibliographyTextParserTest {

    @Test
    fun `finds heading split by IEEEtran small-caps letter spacing`() {
        // Regression test: arXiv 2606.06312 (Meridian) uses IEEEtran.cls, whose small-caps
        // section headings extract with a literal space after the drop cap — "R EFERENCES" —
        // which an exact-line-match heading regex misses entirely, yielding zero references.
        val text = """
            Some concluding paragraph text.
            R EFERENCES
            [1] L. Carlone, A. Kim, "Part1 prelude," in SLAM Handbook, 2021.
            [2] J. Smith, "Another paper," in Some Conference, 2020.
        """.trimIndent()

        val refs = BibliographyTextParser.parse(text)
        assertEquals(2, refs.size)
        assertTrue(refs[0].text.contains("Carlone"))
        assertTrue(refs[1].text.contains("Smith"))
    }

    @Test
    fun `finds plain References heading`() {
        val text = """
            Conclusion text.
            References
            [1] First entry, 2020.
            [2] Second entry, 2021.
        """.trimIndent()

        val refs = BibliographyTextParser.parse(text)
        assertEquals(2, refs.size)
    }

    @Test
    fun `parses author-year style bibliography without numbered markers`() {
        val text = """
            Discussion text.
            Bibliography
            Carlone, L., Kim, A. (2021). Part1 prelude. SLAM Handbook.
            Smith, J. (2020). Another paper. Some Conference.
            Zhao, Y., Lee, K. (2019). Third paper. Some Journal.
        """.trimIndent()

        val refs = BibliographyTextParser.parse(text)
        assertEquals(3, refs.size)
        assertTrue(refs[0].text.startsWith("Carlone"))
        assertTrue(refs[2].text.startsWith("Zhao"))
    }

    @Test
    fun `returns empty list when no heading is found`() {
        val text = "Just a paper with no bibliography section at all."
        assertTrue(BibliographyTextParser.parse(text).isEmpty())
    }

    @Test
    fun `ignores a single stray marker that is not a real reference list`() {
        val text = """
            References
            See note [1] above for details.
        """.trimIndent()
        assertTrue(BibliographyTextParser.parse(text).isEmpty())
    }
}
