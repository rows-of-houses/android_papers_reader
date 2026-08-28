package com.papersreader.app.data.pdf

import com.papersreader.app.data.repository.NormalizedRect
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfTextSelectorTest {

    private fun word(text: String, left: Float, top: Float, bottom: Float = top + 0.02f) =
        PdfWord(text, NormalizedRect(left, top, left + 0.1f, bottom))

    private val words = listOf(
        word("The", left = 0.05f, top = 0.10f),
        word("quick", left = 0.20f, top = 0.10f),
        word("brown", left = 0.35f, top = 0.10f),
        word("fox", left = 0.05f, top = 0.14f),
        word("jumps", left = 0.20f, top = 0.14f),
    )

    @Test
    fun `selects the single word under a tap with no movement`() {
        val selected = PdfTextSelector.wordsInRange(words, fromX = 0.22f, fromY = 0.11f, toX = 0.22f, toY = 0.11f)
        assertEquals(listOf("quick"), selected.map { it.text })
    }

    @Test
    fun `selects every word between two points, in reading order`() {
        val selected = PdfTextSelector.wordsInRange(words, fromX = 0.22f, fromY = 0.11f, toX = 0.22f, toY = 0.15f)
        assertEquals(listOf("quick", "brown", "fox", "jumps"), selected.map { it.text })
    }

    @Test
    fun `selecting backwards yields the same range as forwards`() {
        val forward = PdfTextSelector.wordsInRange(words, fromX = 0.05f, fromY = 0.10f, toX = 0.35f, toY = 0.10f)
        val backward = PdfTextSelector.wordsInRange(words, fromX = 0.35f, fromY = 0.10f, toX = 0.05f, toY = 0.10f)
        assertEquals(forward.map { it.text }, backward.map { it.text })
    }

    @Test
    fun `empty word list selects nothing`() {
        assertEquals(emptyList<PdfWord>(), PdfTextSelector.wordsInRange(emptyList(), 0f, 0f, 1f, 1f))
    }

    @Test
    fun `reassembles same-line words with spaces and line breaks between lines`() {
        val text = PdfTextSelector.textFor(words)
        assertEquals("The quick brown\nfox jumps", text)
    }
}
