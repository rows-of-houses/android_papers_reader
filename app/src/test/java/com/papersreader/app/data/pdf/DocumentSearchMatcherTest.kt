package com.papersreader.app.data.pdf

import com.papersreader.app.data.repository.NormalizedRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentSearchMatcherTest {

    private fun word(text: String) = PdfWord(text, NormalizedRect(0f, 0f, 0.1f, 0.1f))

    private val sample = listOf(
        "The", "Transformer", "model", "uses", "self-attention", "and", "the",
        "Transformer", "generalizes", "well", "another",
    ).map { word(it) }

    @Test
    fun `case-insensitive substring search matches regardless of case`() {
        val matches = DocumentSearchMatcher.findMatches(sample, "transformer", caseSensitive = false, wholeWord = false)
        assertEquals(2, matches.size)
    }

    @Test
    fun `case-sensitive search respects case`() {
        val matches = DocumentSearchMatcher.findMatches(sample, "transformer", caseSensitive = true, wholeWord = false)
        assertTrue(matches.isEmpty())

        val exact = DocumentSearchMatcher.findMatches(sample, "Transformer", caseSensitive = true, wholeWord = false)
        assertEquals(2, exact.size)
    }

    @Test
    fun `whole word search does not match a substring inside a longer word`() {
        val matches = DocumentSearchMatcher.findMatches(sample, "the", caseSensitive = false, wholeWord = true)
        // "The" and "the" as standalone words, but not the "the" inside "another".
        assertEquals(2, matches.size)
    }

    @Test
    fun `non-whole-word search also matches substrings inside other words`() {
        val matches = DocumentSearchMatcher.findMatches(sample, "the", caseSensitive = false, wholeWord = false)
        // "The", "the", plus "the" inside "another" = 3.
        assertEquals(3, matches.size)
    }

    @Test
    fun `multi-word phrase search spans word boundaries`() {
        val matches = DocumentSearchMatcher.findMatches(sample, "Transformer generalizes", caseSensitive = false, wholeWord = false)
        assertEquals(1, matches.size)
        assertEquals(2, matches[0].words.size)
    }

    @Test
    fun `blank query returns no matches`() {
        assertTrue(DocumentSearchMatcher.findMatches(sample, "", caseSensitive = false, wholeWord = false).isEmpty())
    }
}
