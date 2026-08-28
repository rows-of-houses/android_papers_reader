package com.papersreader.app.data.pdf

import com.papersreader.app.data.repository.NormalizedRect

/**
 * Turns a drag gesture into a word-range text selection for copying, reusing the same
 * content-stream reading order [PdfWord] list already used for citations and search — dragging
 * picks the word nearest each endpoint and selects everything between them in that order, the
 * same way a normal text selection extends between two touch points.
 */
object PdfTextSelector {

    /** [fromX]/[fromY]/[toX]/[toY] are normalized (0..1) page coordinates. */
    fun wordsInRange(words: List<PdfWord>, fromX: Float, fromY: Float, toX: Float, toY: Float): List<PdfWord> {
        if (words.isEmpty()) return emptyList()
        val startIndex = nearestWordIndex(words, fromX, fromY)
        val endIndex = nearestWordIndex(words, toX, toY)
        return words.slice(minOf(startIndex, endIndex)..maxOf(startIndex, endIndex))
    }

    /** Reassembles selected words into copyable text, inserting a newline on each line break. */
    fun textFor(words: List<PdfWord>): String {
        val builder = StringBuilder()
        var lastRect: NormalizedRect? = null
        for (word in words) {
            val previous = lastRect
            if (previous != null) builder.append(if (verticallyOverlaps(previous, word.rect)) ' ' else '\n')
            builder.append(word.text)
            lastRect = word.rect
        }
        return builder.toString()
    }

    private fun nearestWordIndex(words: List<PdfWord>, x: Float, y: Float): Int {
        var bestIndex = 0
        var bestDistance = Float.MAX_VALUE
        words.forEachIndexed { index, word ->
            val distance = squaredDistanceToRect(x, y, word.rect)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex
    }

    private fun squaredDistanceToRect(x: Float, y: Float, rect: NormalizedRect): Float {
        val dx = maxOf(rect.left - x, 0f, x - rect.right)
        val dy = maxOf(rect.top - y, 0f, y - rect.bottom)
        return dx * dx + dy * dy
    }

    private fun verticallyOverlaps(a: NormalizedRect, b: NormalizedRect): Boolean =
        a.top < b.bottom && b.top < a.bottom
}
