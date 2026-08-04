package com.papersreader.app.data.pdf

import org.junit.Assert.assertEquals
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
    }
}
