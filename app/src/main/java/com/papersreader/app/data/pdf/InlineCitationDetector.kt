package com.papersreader.app.data.pdf

import com.papersreader.app.data.repository.NormalizedRect

/** A single entry a citation marker resolves to — either a printed bracket number, or an author+year pair matched against the bibliography by content. */
sealed class CitationKey {
    data class Numbered(val index: Int) : CitationKey()
    data class AuthorYear(val surname: String, val year: String) : CitationKey()
}

/**
 * [rects] is one rect *per printed line* the marker spans, not a single bounding box — a
 * multi-line author-year group's bounding box would otherwise stretch across the *full width* of
 * every line it touches, covering ordinary prose in between as a false tap target (confirmed by
 * literally drawing the single-rect version: a 3-line citation group's highlight swallowed an
 * entire unrelated sentence between two of its lines). A list of tighter per-line rects, like a
 * normal multi-line text selection, avoids that.
 */
data class InlineCitation(val rects: List<NormalizedRect>, val keys: List<CitationKey>, val label: String)

/**
 * Finds inline citation markers among a page's words so they can be made tappable in the reader,
 * same as clicking a citation in Scholar PDF Reader — both bracket-style ("[12]", "[3, 7]",
 * "[4-6]") and parenthetical author-year style ("(Vaswani et al., 2017)", "(Peters et al.,
 * 2018a; Radford et al., 2018)").
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
                citations += InlineCitation(listOf(word.rect), direct.map { CitationKey.Numbered(it) }, direct.joinToString(", "))
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
        citations += detectAuthorYear(words)
        return citations
    }

    private fun mergeBracketGroup(words: List<PdfWord>, startIndex: Int): Pair<InlineCitation, Int>? {
        val builder = StringBuilder(words[startIndex].text)
        // One rect per printed line the group spans, not a single bounding box — see
        // InlineCitation's own doc comment for why a bounding box over-reaches on a line wrap.
        val rects = mutableListOf<NormalizedRect>()
        var currentLineRect = words[startIndex].rect
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
            currentLineRect = if (sameLine) union(currentLineRect, next.rect) else next.rect.also { rects += currentLineRect }
            lastWordRect = next.rect
            if (next.text.contains(']')) {
                val indices = parseMarker(builder.toString()) ?: return null
                rects += currentLineRect
                val citation = InlineCitation(rects, indices.map { CitationKey.Numbered(it) }, indices.joinToString(", "))
                return citation to (j + 1)
            }
            j++
        }
        return null
    }

    // --- Author-year style ("(Vaswani et al., 2017)", "(Peters et al., 2018a; Radford et al.,
    // 2018)") -----------------------------------------------------------------------------------
    // Unlike the bracket style, this can't be scanned token-by-token the same way — a citation
    // group's word count varies a lot (one name vs. several names each with "et al." and a
    // year), and matching a name/date grammar against a single token rarely works since PDFBox's
    // splitter breaks on every space regardless of what's semantically one citation. Instead,
    // whole visual lines are reassembled into plain text (reusing the same same-line glue-vs-gap
    // logic as the bracket merger) and matched with a name/year grammar, then the matched
    // character span is mapped back to the underlying words for the tap target.

    private const val NAME_PATTERN = "\\p{Lu}[\\p{L}\\-']+"
    private val parenSpan = Regex("\\(([^()]{4,150})\\)")
    private val onePiece = Regex(
        "^$NAME_PATTERN(?:\\s+(?:and|&)\\s+$NAME_PATTERN|\\s+et\\s*al\\.?)?,?\\s+(\\d{4}[a-z]?)$",
    )
    private val surnameOfPiece = Regex("^($NAME_PATTERN)")

    private fun detectAuthorYear(words: List<PdfWord>): List<InlineCitation> {
        val results = mutableListOf<InlineCitation>()
        for (line in buildBlocks(words)) {
            for (match in parenSpan.findAll(line.text)) {
                val pieces = match.groupValues[1].split(Regex(";\\s*")).map { it.trim() }
                if (pieces.isEmpty()) continue
                val keys = pieces.mapNotNull { piece ->
                    val yearMatch = onePiece.find(piece) ?: return@mapNotNull null
                    val surname = surnameOfPiece.find(piece)?.groupValues?.get(1) ?: return@mapNotNull null
                    CitationKey.AuthorYear(surname, yearMatch.groupValues[1])
                }
                // Every piece must have matched the name/year grammar — a partial match means
                // this parenthetical wasn't actually a citation list ("(see Table 2; also note
                // X)" and the like), not a group with one bad entry to keep anyway.
                if (keys.size != pieces.size) continue

                val spans = line.spans.filter { it.start < match.range.last + 1 && it.end > match.range.first }
                if (spans.isEmpty()) continue
                // Grouped by printed line, not unioned into one box — see InlineCitation's doc
                // comment for why a single bounding box over a multi-line match is a real bug,
                // not just a cosmetic one (it makes unrelated prose in between tappable too).
                val rects = spans.groupBy { it.lineIndex }.values.map { group -> group.map { it.word.rect }.reduce(::union) }
                results += InlineCitation(
                    rects = rects,
                    keys = keys,
                    label = keys.joinToString("; ") { "${it.surname}, ${it.year}" },
                )
            }
        }
        return results
    }

    /** [lineIndex] is which *printed* line within the block this word sits on — used to split a match's rect per line instead of one bounding box (see [InlineCitation]'s doc comment). */
    private data class LineSpan(val word: PdfWord, val start: Int, val end: Int, val lineIndex: Int)
    private data class Line(val text: String, val spans: List<LineSpan>)

    /**
     * Groups words into paragraph-sized blocks of reassembled text — unlike the bracket-style
     * merge above (bounded to [MAX_MERGE_WORDS] words), an author-year citation *group* like
     * "(Dai and Le, 2015; Peters et al., 2018a; Radford et al., 2018; Howard and Ruder, 2018)"
     * routinely wraps across two or three real lines, so matching had to see more than one
     * line's text at a time — same-line-only matching found only the short, single-citation
     * parentheticals that happened to fit on one line, silently missing every grouped one.
     * Consecutive lines are merged as long as each one starts within [isNextLine] of where the
     * last one ended, the same tolerance used for a wrapped bracket group; a bigger jump (a new
     * paragraph, a column break) starts a fresh block instead of pulling in unrelated text.
     */
    private fun buildBlocks(words: List<PdfWord>): List<Line> {
        val blocks = mutableListOf<Line>()
        var current = mutableListOf<PdfWord>()
        for (w in words) {
            val last = current.lastOrNull()
            if (last != null && !verticallyOverlaps(last.rect, w.rect) && !isNextLine(last.rect, w.rect)) {
                blocks += assembleText(current)
                current = mutableListOf()
            }
            current.add(w)
        }
        if (current.isNotEmpty()) blocks += assembleText(current)
        return blocks
    }

    private fun assembleText(words: List<PdfWord>): Line {
        val builder = StringBuilder()
        val spans = mutableListOf<LineSpan>()
        var lastRect: NormalizedRect? = null
        var lineIndex = 0
        for (w in words) {
            val sameLine = lastRect != null && verticallyOverlaps(lastRect, w.rect)
            if (lastRect != null && !sameLine) lineIndex++
            // A line wrap always gets a space (there's no such thing as two real lines glued
            // together with zero gap); within a line, only a real horizontal gap does.
            if (lastRect != null && (!sameLine || horizontalGap(lastRect, w.rect) > GLUED_GAP_THRESHOLD)) builder.append(' ')
            val start = builder.length
            builder.append(w.text)
            spans += LineSpan(w, start, builder.length, lineIndex)
            lastRect = w.rect
        }
        return Line(builder.toString(), spans)
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

/** Matches an [CitationKey.AuthorYear] against a bibliography entry's raw text. */
object AuthorYearMatcher {
    /** How far into the entry's text the first author's name is expected to appear — covers a
     *  "Surname, Initial" opener as well as a first-name-first one ("William B Dolan and..."),
     *  without reaching so far that it might catch the *year itself* mentioning an unrelated year
     *  or a second/third author sharing a common surname. */
    private const val AUTHOR_NAME_WINDOW = 60

    fun matches(referenceText: String, key: CitationKey.AuthorYear): Boolean {
        val opening = referenceText.trimStart().take(AUTHOR_NAME_WINDOW)
        if (!Regex("\\b${Regex.escape(key.surname)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(opening)) return false
        if (referenceText.contains(key.year)) return true
        // The in-text citation may carry a disambiguating suffix ("2018a") that the bibliography
        // entry itself doesn't print if the paper only has one work by this author that year —
        // fall back to a bare-year match so that still resolves instead of finding nothing.
        val numericYear = key.year.trimEnd { it.isLetter() }
        return Regex("\\b$numericYear[a-z]?\\b").containsMatchIn(referenceText)
    }
}
