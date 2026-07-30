package com.papersreader.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TitleSanitizerTest {

    @Test
    fun `strips illegal filesystem characters`() {
        val stem = TitleSanitizer.toFileNameStem("Attention/Is: All*You?Need")
        assertFalse(stem.contains("/"))
        assertFalse(stem.contains(":"))
        assertFalse(stem.contains("*"))
        assertFalse(stem.contains("?"))
    }

    @Test
    fun `collapses repeated whitespace`() {
        val stem = TitleSanitizer.toFileNameStem("Attention   Is\n\nAll  You Need")
        assertEquals("Attention Is All You Need", stem)
    }

    @Test
    fun `falls back to untitled for blank input`() {
        assertEquals("untitled", TitleSanitizer.toFileNameStem("   "))
    }

    @Test
    fun `truncates very long titles`() {
        val longTitle = "A".repeat(300)
        val stem = TitleSanitizer.toFileNameStem(longTitle)
        assertEquals(120, stem.length)
    }

    @Test
    fun `dedupe appends numeric suffix on collision`() {
        val existing = setOf("Paper.pdf", "Paper (2).pdf")
        val result = TitleSanitizer.dedupe("Paper", "pdf") { it in existing }
        assertEquals("Paper (3).pdf", result)
    }

    @Test
    fun `dedupe returns original name when no collision`() {
        val result = TitleSanitizer.dedupe("Paper", "pdf") { false }
        assertEquals("Paper.pdf", result)
    }
}
