package com.papersreader.app.data.pdf

data class InlineCitation(val word: PdfWord, val referenceIndices: List<Int>)

/**
 * Finds bracket-style inline citation markers ("[12]", "[3, 7]", "[4-6]") among a page's words
 * so they can be made tappable in the reader, same as clicking a citation in Scholar PDF
 * Reader. Author-year style ("(Smith, 2020)") isn't handled here — too easy to false-positive
 * on ordinary parenthetical text — only the numbered style [[ReferenceParser]] already parses.
 */
object InlineCitationDetector {

    // Not anchored to the full word: a citation is very often glued to trailing punctuation from
    // the sentence it ends ("prior work [12].", "results [3],"), so we look for the bracket
    // pattern anywhere in the token rather than requiring the whole token to be just "[12]".
    private val rangeRegex = Regex("\\[(\\d{1,3})\\s*[-–]\\s*(\\d{1,3})\\]")
    private val singleOrListRegex = Regex("\\[(\\d{1,3}(?:\\s*,\\s*\\d{1,3})*)\\]")

    fun detect(words: List<PdfWord>): List<InlineCitation> =
        words.mapNotNull { word -> parseMarker(word.text)?.let { InlineCitation(word, it) } }

    private fun parseMarker(text: String): List<Int>? {
        rangeRegex.find(text)?.let { m ->
            val start = m.groupValues[1].toIntOrNull() ?: return null
            val end = m.groupValues[2].toIntOrNull() ?: return null
            if (start > end || end - start > 50) return null
            return (start..end).toList()
        }
        singleOrListRegex.find(text)?.let { m ->
            return m.groupValues[1].split(",").mapNotNull { it.trim().toIntOrNull() }
        }
        return null
    }
}
