package com.papersreader.app.data.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReferenceTitleGuesserTest {

    @Test
    fun `extracts title between author list and venue`() {
        val reference = "K. Kavukcuoglu, P. Sermanet, Y. Boureau, K. Gregor, M. Mathieu, and Y. LeCun. " +
            "Learning convolutional feature hierachies for visual recognition. " +
            "In Advances in Neural Information Processing Systems (NIPS 2010), 2010. 1, 2, 3, 6"
        assertEquals(
            "Learning convolutional feature hierachies for visual recognition",
            ReferenceTitleGuesser.guessTitle(reference),
        )
    }

    @Test
    fun `does not split on single-letter author initials`() {
        val reference = "A. Krizhevsky, I. Sutskever, and G. E. Hinton. " +
            "Imagenet classification with deep convolutional neural networks. In NIPS 2012."
        assertEquals(
            "Imagenet classification with deep convolutional neural networks",
            ReferenceTitleGuesser.guessTitle(reference),
        )
    }

    @Test
    fun `falls back to the full text when the pattern is not found`() {
        val reference = "Just one sentence with no clear author-title split"
        assertEquals(reference, ReferenceTitleGuesser.guessTitle(reference))
        assertNull(ReferenceTitleGuesser.findTitleRange(reference))
    }

    @Test
    fun `strips a dotted numbered marker before finding the title`() {
        val reference = "12. K. He, X. Zhang, S. Ren, J. Sun. Deep Residual Learning for Image Recognition. CVPR 2016."
        assertEquals("Deep Residual Learning for Image Recognition", ReferenceTitleGuesser.guessTitle(reference))
    }

    @Test
    fun `strips a bracket numbered marker before finding the title`() {
        val reference = "[12] K. He, X. Zhang, S. Ren, J. Sun. Deep Residual Learning for Image Recognition. CVPR 2016."
        assertEquals("Deep Residual Learning for Image Recognition", ReferenceTitleGuesser.guessTitle(reference))
    }

    @Test
    fun `strips a natbib alpha coded marker before finding the title`() {
        val reference = "[FB81] Martin A Fischler and Robert C Bolles. Random sample consensus: a paradigm for " +
            "model fitting. Comm. ACM, 1981."
        assertEquals(
            "Random sample consensus: a paradigm for model fitting",
            ReferenceTitleGuesser.guessTitle(reference),
        )
    }

    @Test
    fun `skips a bare year segment in ACL-style entries`() {
        val reference = "Alan Akbik, Duncan Blythe, and Roland Vollgraf. 2018. " +
            "Contextual String Embeddings for Sequence Labeling. Proceedings of COLING."
        assertEquals(
            "Contextual String Embeddings for Sequence Labeling",
            ReferenceTitleGuesser.guessTitle(reference),
        )
    }

    @Test
    fun `findTitleRange points at exactly the substring guessTitle returns`() {
        val reference = "12. K. He, X. Zhang, S. Ren, J. Sun. Deep Residual Learning for Image Recognition. CVPR 2016."
        val range = ReferenceTitleGuesser.findTitleRange(reference)
        checkNotNull(range)
        assertEquals(ReferenceTitleGuesser.guessTitle(reference), reference.substring(range.first, range.last + 1))
    }
}
