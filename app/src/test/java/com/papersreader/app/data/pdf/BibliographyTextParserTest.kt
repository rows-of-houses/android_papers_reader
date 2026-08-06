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
    fun `prefers an author-year bibliography over a later numbered list in an appendix`() {
        // Regression test: BERT's actual paper — an author-year bibliography with no numbered
        // markers of its own, followed later by an appendix FAQ section styled as "1. Question:
        // ... 2. Question: ...". The numbered regex alone finds 2+ matches there and would win by
        // matching *something*, but its first match starts much later in the text than the
        // bibliography's own first entry — that's the signal used to prefer the real one.
        val text = """
            Discussion text.
            References
            Carlone, L., Kim, A. (2021). Part1 prelude. SLAM Handbook.
            Smith, J. (2020). Another paper. Some Conference.

            Appendix

            1. Question: Does the model need this much data? Answer: Yes, it helps.
            2. Question: Does it converge slower? Answer: Only slightly.
        """.trimIndent()

        val refs = BibliographyTextParser.parse(text)
        assertEquals(2, refs.size)
        assertTrue(refs[0].text.startsWith("Carlone"))
        assertTrue(refs[1].text.startsWith("Smith"))
    }

    @Test
    fun `parses ACL Anthology style bibliography with no per-entry marker at all`() {
        // Real excerpt from BERT's own References section (arXiv:1810.04805) — first-name-first
        // author lists, comma-separated, "and Last. YEAR." right before the title, entries
        // running together with no numbering, no blank lines, and no left-margin "Surname,"
        // marker (so neither of the two styles above applies at all).
        val text = """
            References
            Alan Akbik, Duncan Blythe, and Roland Vollgraf.
            2018. Contextual string embeddings for sequence
            labeling. In Proceedings of the 27th International
            Conference on Computational Linguistics, pages
            1638-1649.
            Rami Al-Rfou, Dokook Choe, Noah Constant, Mandy
            Guo, and Llion Jones. 2018. Character-level language modeling with deeper self-attention. arXiv
            preprint arXiv:1808.04444.
            Rie Kubota Ando and Tong Zhang. 2005. A framework
            for learning predictive structures from multiple tasks
            and unlabeled data. Journal of Machine Learning
            Research, 6(Nov):1817-1853.
            John Blitzer, Ryan McDonald, and Fernando Pereira.
            2006. Domain adaptation with structural correspondence learning. In TAC. NIST.
        """.trimIndent()

        val refs = BibliographyTextParser.parse(text)
        assertEquals(4, refs.size)
        assertTrue(refs[0].text.startsWith("Alan Akbik"))
        assertTrue(refs[1].text.startsWith("Rami Al-Rfou"))
        assertTrue(refs[2].text.startsWith("Rie Kubota Ando"))
        assertTrue(refs[3].text.startsWith("John Blitzer"))
        // "In TAC. NIST." (venue abbreviations, all caps) must not be mistaken for the next
        // entry's author list and swallow the fourth entry's own trailing text.
        assertTrue(refs[3].text.contains("NIST"))
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
