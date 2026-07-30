package com.papersreader.app.data.network

import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceMatcherTest {

    @Test
    fun `matching title scores above threshold`() {
        val reference = "[1] Jimmy Lei Ba, Jamie Ryan Kiros, and Geoffrey E Hinton. " +
            "Layer normalization. arXiv preprint arXiv:1607.06450, 2016."
        val score = ReferenceMatcher.titleOverlapScore("Layer normalization", reference)
        assertTrue("expected a confident match, got $score", score >= ReferenceMatcher.MIN_MATCH_SCORE)
    }

    @Test
    fun `unrelated title scores below threshold`() {
        // Regression test: Crossref's free-text search once matched this reference to an
        // unrelated preprints.org comment about quantum statistics — the exact failure mode
        // ReferenceMatcher exists to catch.
        val reference = "[1] Jimmy Lei Ba, Jamie Ryan Kiros, and Geoffrey E Hinton. " +
            "Layer normalization. arXiv preprint arXiv:1607.06450, 2016."
        val unrelatedTitle = "Comment on the Paper Titled 'The Origin of Quantum Mechanical " +
            "Statistics: Insights from Research on Human Language'"
        val score = ReferenceMatcher.titleOverlapScore(unrelatedTitle, reference)
        assertTrue("expected a weak match, got $score", score < ReferenceMatcher.MIN_MATCH_SCORE)
    }

    @Test
    fun `empty candidate title never matches`() {
        val score = ReferenceMatcher.titleOverlapScore("", "Attention is all you need")
        assertTrue(score == 0.0)
    }

    @Test
    fun `significant words drops stopwords and short tokens`() {
        val words = ReferenceMatcher.significantWords("The Attention Is All You Need, a paper")
        assertTrue("stopword leaked into $words", "the" !in words)
        assertTrue("short token leaked into $words", "a" !in words)
        assertTrue("attention" in words)
        assertTrue("need" in words)
    }
}
