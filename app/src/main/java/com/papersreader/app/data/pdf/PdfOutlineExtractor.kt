package com.papersreader.app.data.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import timber.log.Timber
import java.io.File

data class OutlineEntry(val title: String, val page: Int, val level: Int)

/**
 * Reads the PDF's embedded table of contents (bookmarks), the same "outline" a desktop reader
 * shows in its sidebar. Most LaTeX papers built with `hyperref` have one; scanned/plain PDFs
 * generally don't, in which case this returns an empty list and the reader simply hides the
 * table-of-contents entry point rather than trying to fabricate one.
 */
object PdfOutlineExtractor {

    fun extract(file: File): List<OutlineEntry> {
        return try {
            PDDocument.load(file).use { doc -> extract(doc) }
        } catch (e: Exception) {
            Timber.w(e, "Failed to extract outline from ${file.name}")
            emptyList()
        }
    }

    private fun extract(doc: PDDocument): List<OutlineEntry> {
        val root = doc.documentCatalog?.documentOutline ?: return emptyList()
        val pageNumbers = HashMap<PDPage, Int>()
        doc.pages.forEachIndexed { index, page -> pageNumbers[page] = index }

        val entries = mutableListOf<OutlineEntry>()
        collect(root, level = 0, doc = doc, pageNumbers = pageNumbers, out = entries)
        return entries
    }

    private fun collect(
        node: PDOutlineNode,
        level: Int,
        doc: PDDocument,
        pageNumbers: Map<PDPage, Int>,
        out: MutableList<OutlineEntry>,
    ) {
        var child = node.firstChild
        while (child != null) {
            val page = resolvePageNumber(child, doc, pageNumbers)
            if (page != null && !child.title.isNullOrBlank()) {
                out += OutlineEntry(title = child.title.trim(), page = page, level = level)
            }
            collect(child, level + 1, doc, pageNumbers, out)
            child = child.nextSibling
        }
    }

    private fun resolvePageNumber(item: PDOutlineItem, doc: PDDocument, pageNumbers: Map<PDPage, Int>): Int? {
        return try {
            val destPage = item.findDestinationPage(doc) ?: return null
            pageNumbers[destPage]
        } catch (e: Exception) {
            null
        }
    }
}
