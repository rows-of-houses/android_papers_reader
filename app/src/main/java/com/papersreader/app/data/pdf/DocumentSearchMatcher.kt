package com.papersreader.app.data.pdf

data class SearchMatch(val words: List<PdfWord>)

/**
 * In-document text search over a page's word boxes, supporting the same options as a normal
 * desktop find-in-page: case sensitivity and whole-word matching. Multi-word queries are
 * matched as a phrase against the page's reconstructed text (words joined by single spaces).
 */
object DocumentSearchMatcher {

    fun findMatches(words: List<PdfWord>, query: String, caseSensitive: Boolean, wholeWord: Boolean): List<SearchMatch> {
        if (query.isBlank() || words.isEmpty()) return emptyList()

        val sb = StringBuilder()
        // wordAtOffset[i] = index into `words` that character i of `sb` belongs to, or -1 for
        // the single space we insert between words.
        val wordAtOffset = mutableListOf<Int>()
        words.forEachIndexed { index, word ->
            if (sb.isNotEmpty()) {
                sb.append(' ')
                wordAtOffset.add(-1)
            }
            repeat(word.text.length) { wordAtOffset.add(index) }
            sb.append(word.text)
        }

        val haystack = if (caseSensitive) sb.toString() else sb.toString().lowercase()
        val needle = if (caseSensitive) query else query.lowercase()

        val matches = mutableListOf<SearchMatch>()
        var searchFrom = 0
        while (searchFrom <= haystack.length) {
            val start = haystack.indexOf(needle, searchFrom)
            if (start < 0) break
            val end = start + needle.length

            if (!wholeWord || isWholeWordMatch(haystack, start, end)) {
                val wordIndices = (start until end).mapNotNull { wordAtOffset.getOrNull(it) }.filter { it >= 0 }.distinct()
                if (wordIndices.isNotEmpty()) {
                    matches += SearchMatch(wordIndices.map { words[it] })
                }
            }
            searchFrom = start + 1
        }
        return matches
    }

    private fun isWholeWordMatch(haystack: String, start: Int, end: Int): Boolean {
        val beforeOk = start == 0 || !haystack[start - 1].isLetterOrDigit()
        val afterOk = end >= haystack.length || !haystack[end].isLetterOrDigit()
        return beforeOk && afterOk
    }
}
