package com.papersreader.app.data.pdf

import com.papersreader.app.data.repository.NormalizedRect

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

    /** How many whitespace-split words a single "[a, b, c]" group may be glued back together from. */
    private const val MAX_MERGE_WORDS = 8

    fun detect(words: List<PdfWord>): List<InlineCitation> {
        val citations = mutableListOf<InlineCitation>()
        var i = 0
        while (i < words.size) {
            val word = words[i]
            val direct = parseMarker(word.text)
            if (direct != null) {
                citations += InlineCitation(word, direct)
                i++
                continue
            }
            // PDFBox's word splitter cuts on whitespace, so a spaced-out group like
            // "[38, 24, 15]" arrives as three separate words ("[38,", "24,", "15].") that none
            // of them individually match a bracket regex. Glue them back together, provided
            // they stay on the same visual line, before giving up on this position.
            if (word.text.contains('[') && !word.text.contains(']')) {
                val merged = mergeBracketGroup(words, i)
                if (merged != null) {
                    citations += merged.first
                    i = merged.second
                    continue
                }
            }
            i++
        }
        return citations
    }

    private fun mergeBracketGroup(words: List<PdfWord>, startIndex: Int): Pair<InlineCitation, Int>? {
        val builder = StringBuilder(words[startIndex].text)
        var rect = words[startIndex].rect
        // Compared against for line-adjacency/gluing on the *next* step — the accumulated `rect`
        // above grows to span every merged word, so once a group has wrapped onto a second line
        // its height would otherwise look like "one giant line" and throw off both the same-line
        // glue check and the next-line tolerance for word 3+.
        var lastWordRect = words[startIndex].rect
        var j = startIndex + 1
        while (j < words.size && j - startIndex < MAX_MERGE_WORDS) {
            val next = words[j]
            val sameLine = verticallyOverlaps(lastWordRect, next.rect)
            // A long group like "[37, 29, 15, 31, 14, 36]" can wrap across a real line break in
            // narrow (e.g. two-column) layouts — the closing "]" ends up on the line below the
            // opening "[". Content-stream order (which this word list is already in — see
            // PdfWordExtractor/memory notes on why sortByPosition breaks 2-column PDFs) keeps
            // column-adjacent words together, so trusting "next word in list order, one line
            // below" doesn't risk jumping into an unrelated column the way an x/y-only proximity
            // check would.
            if (!sameLine && !isNextLine(lastWordRect, next.rect)) return null
            // PDFBox sometimes reports "[13]" as separate words ("[" then "13]") even without a
            // real space in the source — e.g. a font/style change mid-run. Only insert a space
            // when there's an actual visible gap (same line) or a genuine line wrap, or a glued
            // "[" + "13]" becomes "[ 13]" and no longer matches the marker regex (which requires
            // a digit immediately after "[").
            if (!sameLine || horizontalGap(lastWordRect, next.rect) > GLUED_GAP_THRESHOLD) builder.append(' ')
            builder.append(next.text)
            rect = union(rect, next.rect)
            lastWordRect = next.rect
            if (next.text.contains(']')) {
                val indices = parseMarker(builder.toString()) ?: return null
                return InlineCitation(PdfWord(builder.toString(), rect), indices) to (j + 1)
            }
            j++
        }
        return null
    }

    /** Normalized-width gap between the right edge of [a] and the left edge of [b]. */
    private fun horizontalGap(a: NormalizedRect, b: NormalizedRect): Float = b.left - a.right

    /** Below this, two words are treated as visually glued (no real space in the source PDF). */
    private const val GLUED_GAP_THRESHOLD = 0.0015f

    /** How many line-heights below is still "the very next line", not a jump to a new paragraph. */
    private const val LINE_WRAP_TOLERANCE = 1.8f

    private fun verticallyOverlaps(a: NormalizedRect, b: NormalizedRect): Boolean =
        a.top < b.bottom && b.top < a.bottom

    private fun isNextLine(a: NormalizedRect, b: NormalizedRect): Boolean {
        val lineHeight = a.bottom - a.top
        if (lineHeight <= 0f) return false
        val verticalGap = b.top - a.bottom
        return verticalGap in (-lineHeight * 0.3f)..(lineHeight * LINE_WRAP_TOLERANCE)
    }

    private fun union(a: NormalizedRect, b: NormalizedRect): NormalizedRect = NormalizedRect(
        left = minOf(a.left, b.left),
        top = minOf(a.top, b.top),
        right = maxOf(a.right, b.right),
        bottom = maxOf(a.bottom, b.bottom),
    )

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
