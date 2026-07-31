package com.papersreader.app.data.pdf

import com.papersreader.app.data.repository.NormalizedRect
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import timber.log.Timber
import java.io.File

/** One whitespace-delimited token on a page, with its bounding box normalized to 0..1. */
data class PdfWord(val text: String, val rect: NormalizedRect)

/**
 * Extracts words-with-positions for a single page, used to make inline citation markers
 * ("[12]") tappable and to highlight in-document search matches. Kept separate from
 * [ReferenceParser] (which needs the *whole* document's text in reading order) since this
 * needs precise per-character coordinates instead.
 */
object PdfWordExtractor {

    fun extractWords(file: File, pageIndex: Int): List<PdfWord> {
        return try {
            PDDocument.load(file).use { doc -> extractWords(doc, pageIndex) }
        } catch (e: Exception) {
            Timber.w(e, "Failed to extract words for page $pageIndex of ${file.name}")
            emptyList()
        }
    }

    /** Extracts words for every page in one pass — used for whole-document search. */
    fun extractAllPages(file: File): Map<Int, List<PdfWord>> {
        return try {
            PDDocument.load(file).use { doc ->
                (0 until doc.numberOfPages).associateWith { pageIndex -> extractWords(doc, pageIndex) }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to extract words from ${file.name}")
            emptyMap()
        }
    }

    private fun extractWords(doc: PDDocument, pageIndex: Int): List<PdfWord> {
        if (pageIndex !in 0 until doc.numberOfPages) return emptyList()
        val page = doc.getPage(pageIndex)
        val pageWidth = page.mediaBox.width
        val pageHeight = page.mediaBox.height
        if (pageWidth <= 0f || pageHeight <= 0f) return emptyList()

        val words = mutableListOf<PdfWord>()
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                words += splitIntoWords(text, textPositions, pageWidth, pageHeight)
            }
        }
        stripper.startPage = pageIndex + 1
        stripper.endPage = pageIndex + 1
        stripper.getText(doc)
        return words
    }

    /**
     * PDFBox guarantees `textPositions.size == text.length` for a single writeString call, so
     * we walk both in lockstep and cut a new word on whitespace.
     */
    private fun splitIntoWords(
        text: String,
        textPositions: List<TextPosition>,
        pageWidth: Float,
        pageHeight: Float,
    ): List<PdfWord> {
        if (text.length != textPositions.size) return emptyList()

        val result = mutableListOf<PdfWord>()
        var wordStart = -1
        fun flush(endExclusive: Int) {
            if (wordStart < 0 || endExclusive <= wordStart) return
            val wordText = text.substring(wordStart, endExclusive)
            val positions = textPositions.subList(wordStart, endExclusive)
            boundingRect(positions, pageWidth, pageHeight)?.let { rect ->
                result += PdfWord(wordText, rect)
            }
            wordStart = -1
        }

        for (i in text.indices) {
            if (text[i].isWhitespace()) {
                flush(i)
            } else if (wordStart < 0) {
                wordStart = i
            }
        }
        flush(text.length)
        return result
    }

    private fun boundingRect(positions: List<TextPosition>, pageWidth: Float, pageHeight: Float): NormalizedRect? {
        if (positions.isEmpty()) return null
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = Float.MIN_VALUE
        var bottom = Float.MIN_VALUE
        for (p in positions) {
            val x = p.xDirAdj
            val y = p.yDirAdj
            left = minOf(left, x)
            top = minOf(top, y - p.heightDir)
            right = maxOf(right, x + p.widthDirAdj)
            bottom = maxOf(bottom, y)
        }
        return NormalizedRect(
            left = (left / pageWidth).coerceIn(0f, 1f),
            top = (top / pageHeight).coerceIn(0f, 1f),
            right = (right / pageWidth).coerceIn(0f, 1f),
            bottom = (bottom / pageHeight).coerceIn(0f, 1f),
        )
    }
}
