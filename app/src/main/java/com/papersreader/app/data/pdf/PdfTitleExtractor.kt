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
private data class TextRun(
    val text: String,
    val y: Float,
    val fontSize: Float,
    val order: Int,
    val xStart: Float,
    val xEnd: Float,
    val spaceWidth: Float,
)

private data class LineCandidate(val text: String, val avgFontSize: Float, val order: Int)

/**
 * Papers are rarely named anything useful when they land on a phone ("document.pdf",
 * "2403.01234v2.pdf"...), so we recover the real title from the PDF itself: first the
 * embedded document-info title, then a heuristic read of the first page.
 */
object PdfTitleExtractor {

    private val yearRegex = Regex("\\b(19|20)\\d{2}\\b")

    // arXiv IDs are YYMM.NNNNN — the year is encoded right in the identifier, unambiguously and
    // independent of *when this particular PDF happened to be re-rendered/downloaded*. Far more
    // reliable than scanning page 1 for "the first 4-digit number that looks like a year", which
    // just as easily matches an early citation year mentioned in the introduction (e.g. "the
    // ImageNet moment... 2012" on a 2022 paper's first page, or an earlier related-work citation
    // on page 1 of a paper from a different year).
    private val arxivIdRegex = Regex("arXiv:(\\d{2})(\\d{2})\\.\\d{4,5}")

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
                    else -> (guessTitleByFontSize(lines) ?: fallbackTitle).let(::cleanUpHyphenSpacing)
                }

                val pageText = lines.joinToString("\n") { it.text }
                val year = arxivIdRegex.find(pageText)?.let { 2000 + it.groupValues[1].toInt() }
                    ?: yearRegex.find(pageText)?.value?.toIntOrNull()

                ExtractedMetadata(
                    title = title,
                    authors = info?.author?.trim()?.takeIf { it.isNotBlank() },
                    year = year,
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
                    val last = textPositions.last()
                    val avgSpaceWidth = textPositions.map { it.widthOfSpace }.filter { it > 0f }
                        .let { if (it.isEmpty()) 0f else it.average().toFloat() }
                    runs.add(TextRun(text, avgY, avgSize, runs.size, textPositions.first().x, last.x + last.width, avgSpaceWidth))
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
            // Titles styled with a larger "drop cap" first letter per word (a common LaTeX
            // template look) report that letter as its own run, at a different font size, with
            // essentially zero gap to the rest of the word — always inserting a space between
            // runs here turned "VERY DEEP..." into "V ERY D EEP...". Only insert one when the
            // horizontal gap to the previous run is a real fraction of a space character's
            // width, not just whenever a new run starts.
            val builder = StringBuilder()
            var prevXEnd: Float? = null
            var prevSpaceWidth = 0f
            for (run in bucket) {
                val trimmed = run.text.trim()
                if (trimmed.isEmpty()) continue
                if (prevXEnd != null) {
                    val gap = run.xStart - prevXEnd
                    // A "drop cap" larger first letter (a common LaTeX title style — see below)
                    // still leaves a real, if smaller, gap to the rest of its own word — measured
                    // in a real example at ~0.5-0.6x the runs' own space width, vs. ~0.9-1x+ for
                    // an actual word-to-word space. 0.7x sits between the two.
                    val threshold = maxOf(prevSpaceWidth, run.spaceWidth) * 0.7f
                    if (threshold <= 0f || gap > threshold) builder.append(' ')
                }
                builder.append(trimmed)
                prevXEnd = run.xEnd
                prevSpaceWidth = run.spaceWidth
            }
            val text = builder.toString().replace(Regex("\\s+"), " ").trim()
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

    /**
     * PDFs assembled/converted from other tools (ps2pdf, gnuplot, LaTeX figure includes...) can
     * leave the *document-level* `/Title` set to some unrelated fragment's own title instead of
     * the paper's — observed in the wild: `large.eps` (a gnuplot figure's title leaking into the
     * merged PDF) and `+0.8% 28M` (a chart data-point label). Neither is a filename-only pattern
     * nor an exact "untitled"-style match, so beyond the filename-extension check, also reject
     * anything that isn't mostly letters — a real paper title is never mostly digits/symbols.
     */
    // Even with the run-gluing fix above, a compound word split across a hyphen at a run
    // boundary (e.g. "LARGE" / "-SCALE" from a drop-cap "L" + "ARGE" + "-SCALE") can still line
    // up with just enough of a gap to count as a real word-space, leaving "LARGE -SCALE" instead
    // of "LARGE-SCALE". A bare "<space>-<non-space>" is essentially never intentional in a paper
    // title (an intentional dash is spaced on *both* sides, "A - B"), so it's safe to collapse.
    private val strayHyphenSpace = Regex("\\s+-(?=\\S)")

    private fun cleanUpHyphenSpacing(text: String): String = text.replace(strayHyphenSpace, "-")

    private fun looksGeneric(title: String): Boolean {
        val normalized = title.lowercase()
        if (normalized in setOf("untitled", "document", "microsoft word - document")) return true
        if (Regex("^[a-z0-9_-]+\\.(pdf|docx?|tex|eps|ps|png|jpe?g|svg|ai|fig|gp)$").matches(normalized)) return true
        val letterCount = title.count { it.isLetter() }
        return letterCount < title.length * 0.5
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
