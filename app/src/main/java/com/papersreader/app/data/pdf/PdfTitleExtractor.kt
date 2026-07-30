package com.papersreader.app.data.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import timber.log.Timber
import java.io.File

data class ExtractedMetadata(
    val title: String,
    val authors: String?,
    val year: Int?,
    val pageCount: Int,
)

/** One contiguous run of text as PDFTextStripper emits it — a visual line can span several runs. */
private data class TextRun(val text: String, val y: Float, val fontSize: Float, val order: Int)

private data class LineCandidate(val text: String, val avgFontSize: Float, val order: Int)

/**
 * Papers are rarely named anything useful when they land on a phone ("document.pdf",
 * "2403.01234v2.pdf"...), so we recover the real title from the PDF itself: first the
 * embedded document-info title, then a heuristic read of the first page.
 */
object PdfTitleExtractor {

    private val yearRegex = Regex("\\b(19|20)\\d{2}\\b")

    /** Runs whose baseline Y differs by less than this (in PDF points) are the same visual line. */
    private const val SAME_LINE_Y_TOLERANCE = 3f

    fun extract(file: File, fallbackTitle: String): ExtractedMetadata {
        return try {
            PDDocument.load(file).use { doc ->
                val info = doc.documentInformation
                val metadataTitle = info?.title?.trim()
                val lines = reconstructFirstPageLines(doc)

                val title = when {
                    !metadataTitle.isNullOrBlank() && !looksGeneric(metadataTitle) -> metadataTitle
                    else -> guessTitleByFontSize(lines) ?: fallbackTitle
                }

                ExtractedMetadata(
                    title = title,
                    authors = info?.author?.trim()?.takeIf { it.isNotBlank() },
                    year = yearRegex.find(lines.joinToString("\n") { it.text })?.value?.toIntOrNull(),
                    pageCount = doc.numberOfPages,
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to extract PDF metadata for ${file.name}, falling back to file name")
            ExtractedMetadata(title = fallbackTitle, authors = null, year = null, pageCount = 0)
        }
    }

    /**
     * PDFTextStripper's writeString callback fires once per contiguous text-positioning run,
     * which for kerned/justified text can split a single visual line (e.g. a title) into
     * several runs — "Attention" and "Is All You Need" can arrive as two separate calls even
     * though they sit on the same line. We collect the raw runs first, then merge consecutive
     * ones that share (almost) the same Y baseline back into real lines before scoring them.
     */
    private fun reconstructFirstPageLines(doc: PDDocument): List<LineCandidate> {
        val runs = mutableListOf<TextRun>()
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                if (text.isNotBlank() && textPositions.isNotEmpty()) {
                    val avgY = textPositions.map { it.y }.average().toFloat()
                    val avgSize = textPositions.map { it.fontSizeInPt }.average().toFloat()
                    runs.add(TextRun(text, avgY, avgSize, runs.size))
                }
            }
        }
        stripper.sortByPosition = true
        stripper.startPage = 1
        stripper.endPage = 1
        stripper.getText(doc)

        val lines = mutableListOf<LineCandidate>()
        var bucket = mutableListOf<TextRun>()
        fun flushBucket() {
            if (bucket.isEmpty()) return
            val text = bucket.joinToString(" ") { it.text.trim() }.replace(Regex("\\s+"), " ").trim()
            val avgSize = bucket.map { it.fontSize }.average().toFloat()
            if (text.isNotEmpty()) lines.add(LineCandidate(text, avgSize, lines.size))
            bucket = mutableListOf()
        }
        for (run in runs) {
            val last = bucket.lastOrNull()
            if (last != null && kotlin.math.abs(run.y - last.y) > SAME_LINE_Y_TOLERANCE) {
                flushBucket()
            }
            bucket.add(run)
        }
        flushBucket()
        return lines
    }

    private fun looksGeneric(title: String): Boolean {
        val normalized = title.lowercase()
        return normalized in setOf("untitled", "document", "microsoft word - document") ||
            Regex("^[a-z0-9_-]+\\.(pdf|docx?|tex)$").matches(normalized)
    }

    private val boilerplateFragments = listOf(
        "arxiv:", "hereby grants", "attribution is provided", "all rights reserved",
        "licensed under", "preprint", "under review", "conference on", "proceedings of",
        "©", "copyright", "non-exclusive", "distribute this", "solely for",
    )

    /** Among the first ~25 reconstructed lines of the page, picks the one set in the largest font. */
    private fun guessTitleByFontSize(lines: List<LineCandidate>): String? {
        val candidates = lines
            .take(25)
            .filter { it.text.length in 8..200 }
            .filter { line -> boilerplateFragments.none { line.text.contains(it, ignoreCase = true) } }
            .filter { !Regex("^(abstract|introduction|\\d+)$", RegexOption.IGNORE_CASE).matches(it.text) }
        if (candidates.isEmpty()) return null

        val maxFontSize = candidates.maxOf { it.avgFontSize }
        // A title can wrap across two adjacent lines set in the same large font; join them.
        val largestLines = candidates
            .filter { it.avgFontSize >= maxFontSize - 0.5f }
            .sortedBy { it.order }
        return largestLines.joinToString(" ") { it.text }.take(300)
    }
}
