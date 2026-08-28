package com.papersreader.app.data.pdf

import com.papersreader.app.data.repository.NormalizedRect

/**
 * Turns a drag gesture into a word-range text selection for copying, reusing the same
 * content-stream reading order [PdfWord] list already used for citations and search — dragging
 * picks the word nearest each endpoint and selects everything between them in that order, the
 * same way a normal text selection extends between two touch points.
 */
object PdfTextSelector {

    /**
     * [fromX]/[fromY]/[toX]/[toY] are normalized (0..1) page coordinates. [pageAspectRatio] is
     * the page's real `heightPx / widthPx` — needed because normalized coordinates alone aren't
     * isotropic: a page is almost never square, so an equal-normalized-distance change in x and
     * y corresponds to different real pixel distances, and comparing raw normalized deltas
     * against each other (as if 0.01 horizontally and 0.01 vertically were the same physical gap)
     * systematically picks the wrong "nearest" word — most visibly on a typical tall page, where
     * it under-counts vertical distance and can jump the touch point to a word a line or two away
     * from where a finger actually landed. Left at 1 (no correction) by default so existing
     * square-page-agnostic callers/tests are unaffected; real UI code should always pass the
     * page's actual aspect ratio.
     */
    fun wordsInRange(
        words: List<PdfWord>,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        pageAspectRatio: Float = 1f,
    ): List<PdfWord> {
        if (words.isEmpty()) return emptyList()
        val startIndex = nearestWordIndex(words, fromX, fromY, pageAspectRatio)
        val endIndex = nearestWordIndex(words, toX, toY, pageAspectRatio)
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

    private fun nearestWordIndex(words: List<PdfWord>, x: Float, y: Float, pageAspectRatio: Float): Int {
        var bestIndex = 0
        var bestDistance = Float.MAX_VALUE
        words.forEachIndexed { index, word ->
            val distance = squaredDistanceToRect(x, y, word.rect, pageAspectRatio)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex
    }

    private fun squaredDistanceToRect(x: Float, y: Float, rect: NormalizedRect, pageAspectRatio: Float): Float {
        val dx = maxOf(rect.left - x, 0f, x - rect.right)
        val dy = maxOf(rect.top - y, 0f, y - rect.bottom) * pageAspectRatio
        return dx * dx + dy * dy
    }

    private fun verticallyOverlaps(a: NormalizedRect, b: NormalizedRect): Boolean =
        a.top < b.bottom && b.top < a.bottom
}
