package com.papersreader.app.data.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import timber.log.Timber
import java.io.File

data class ParsedReference(val index: Int, val text: String)

/**
 * Finds the bibliography section of a paper and splits it into individual reference strings.
 * See [BibliographyTextParser] for the actual (unit-tested) text heuristics; this class is
 * just the PDFBox glue to get plain text out of the PDF.
 */
object ReferenceParser {

    fun parse(file: File): List<ParsedReference> {
        return try {
            PDDocument.load(file).use { doc -> parse(doc) }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse references from ${file.name}")
            emptyList()
        }
    }

    private fun parse(doc: PDDocument): List<ParsedReference> {
        // sortByPosition=true sorts purely by Y-then-X across the *whole page width*, which
        // interleaves left/right column text line-by-line on two-column (IEEE-style) papers —
        // confirmed by manual testing where it spliced "REFERENCES" into the middle of an
        // unrelated bibliography entry. Content-stream order is what we want here: PDF
        // generators (LaTeX/pdfTeX) emit text column-by-column in true reading order already.
        val stripper = PDFTextStripper()
        val fullText = stripper.getText(doc)
        return BibliographyTextParser.parse(fullText)
    }
}
