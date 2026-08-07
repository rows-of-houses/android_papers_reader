package com.papersreader.app.ui.reader

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papersreader.app.data.pdf.AuthorYearMatcher
import com.papersreader.app.data.pdf.CitationKey
import com.papersreader.app.data.pdf.CodedMatcher
import com.papersreader.app.data.pdf.DocumentSearchMatcher
import com.papersreader.app.data.pdf.InlineCitation
import com.papersreader.app.data.pdf.OutlineEntry
import com.papersreader.app.data.pdf.ParsedReference
import com.papersreader.app.data.pdf.PdfOutlineExtractor
import com.papersreader.app.data.pdf.PdfPageRenderer
import com.papersreader.app.data.pdf.PdfWord
import com.papersreader.app.data.pdf.PdfWordExtractor
import com.papersreader.app.data.pdf.ReferenceTitleGuesser
import com.papersreader.app.data.pdf.SearchMatch
import com.papersreader.app.data.repository.Annotation
import com.papersreader.app.data.repository.AnnotationRepository
import com.papersreader.app.data.repository.LibraryRepository
import com.papersreader.app.data.repository.NormalizedRect
import com.papersreader.app.data.repository.ReferenceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

enum class ReaderMode { VIEW, HIGHLIGHT, NOTE, DRAW }

data class SearchState(
    val active: Boolean = false,
    val query: String = "",
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val loading: Boolean = false,
    val matches: List<PageSearchMatch> = emptyList(),
    val currentMatchIndex: Int = -1,
)

data class PageSearchMatch(val page: Int, val match: SearchMatch)

/** Marker colors offered for freehand drawing, in the order shown in the picker. */
enum class MarkerColor(val argb: Int) {
    BLACK(0xFF000000.toInt()),
    RED(0xFFE53935.toInt()),
    GREEN(0xFF2E7D32.toInt()),
}

/** Marker stroke widths offered for freehand drawing (raw canvas px, same scale as the old constant default). */
enum class MarkerThickness(val px: Float) {
    THIN(4f),
    MEDIUM(8f),
    THICK(14f),
}

data class ReaderUiState(
    val title: String = "",
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val mode: ReaderMode = ReaderMode.VIEW,
    val references: List<ParsedReference> = emptyList(),
    val referencesLoading: Boolean = false,
    val resolvingReference: Boolean = false,
    val outline: List<OutlineEntry> = emptyList(),
    /** Zoom the paper was last closed at, applied once when the reader first opens it. */
    val initialZoom: Float = 1f,
    val jumpToPage: Int? = null,
    val error: String? = null,
    val markerColor: MarkerColor = MarkerColor.RED,
    val markerThickness: MarkerThickness = MarkerThickness.MEDIUM,
    val canUndoAnnotation: Boolean = false,
    val downloadingReferenceIndex: Int? = null,
    val libraryMessage: String? = null,
    val openPaperId: Long? = null,
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val annotationRepository: AnnotationRepository,
    private val referenceRepository: ReferenceRepository,
) : ViewModel() {

    private var renderer: PdfPageRenderer? = null
    private var file: File? = null
    private var paperId: Long = -1
    private val paperIdFlow = MutableStateFlow<Long?>(null)

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState

    private val currentPageFlow = MutableStateFlow(0)

    private val _searchState = MutableStateFlow(SearchState())
    val searchState: StateFlow<SearchState> = _searchState

    /**
     * Per-page word positions (for inline citations, and reused for search) — extracted for
     * every page in a single pass on open. Originally this loaded words per-page on demand,
     * but each call reopened the whole PDF from scratch; since the pager composes every page's
     * words eagerly anyway, that meant re-parsing a 15-page document 15 times (~15s before the
     * first citation became tappable). A single [PdfWordExtractor.extractAllPages] pass is both
     * simpler and an order of magnitude faster.
     */
    private val _pageWords = MutableStateFlow<Map<Int, List<PdfWord>>>(emptyMap())
    val pageWords: StateFlow<Map<Int, List<PdfWord>>> = _pageWords

    /**
     * All of the paper's annotations, not just the "current" page's — with continuous
     * vertical scroll several pages can be visible/interacted with at once, so each page needs
     * its own slice of this rather than a single current-page-only list.
     */
    val paperAnnotations: StateFlow<List<Annotation>> = paperIdFlow.flatMapLatest { id ->
        if (id == null) kotlinx.coroutines.flow.flowOf(emptyList()) else annotationRepository.observeForPaper(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun open(paperId: Long) {
        if (this.paperId == paperId) return
        this.paperId = paperId
        paperIdFlow.value = paperId
        viewModelScope.launch {
            val paper = libraryRepository.getPaper(paperId) ?: run {
                _uiState.value = _uiState.value.copy(error = "Paper not found")
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                title = paper.title,
                currentPage = paper.lastPage,
                initialZoom = paper.lastZoom,
            )
            currentPageFlow.value = paper.lastPage

            val paperFile = libraryRepository.paperFile(paper)
            file = paperFile
            val newRenderer = withContext(Dispatchers.IO) { PdfPageRenderer(paperFile) }
            renderer = newRenderer
            _uiState.value = _uiState.value.copy(pageCount = newRenderer.pageCount)

            loadReferences(paperFile)
            loadOutline(paperFile)
            loadAllPageWords(paperFile)
        }
    }

    private fun loadAllPageWords(file: File) {
        viewModelScope.launch {
            val words = withContext(Dispatchers.Default) { PdfWordExtractor.extractAllPages(file) }
            _pageWords.value = words
        }
    }

    private fun loadReferences(file: File) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(referencesLoading = true)
            val refs = withContext(Dispatchers.Default) { referenceRepository.parseReferences(file) }
            _uiState.value = _uiState.value.copy(references = refs, referencesLoading = false)
        }
    }

    private fun loadOutline(file: File) {
        viewModelScope.launch {
            val outline = withContext(Dispatchers.Default) { PdfOutlineExtractor.extract(file) }
            _uiState.value = _uiState.value.copy(outline = outline)
        }
    }

    suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap? =
        runCatching { renderer?.renderPage(pageIndex, targetWidthPx) }
            .onFailure { Timber.e(it, "Failed to render page $pageIndex") }
            .getOrNull()

    suspend fun pageAspectRatio(pageIndex: Int): Float =
        runCatching { renderer?.pageAspectRatio(pageIndex) }.getOrNull() ?: (1f / 1.414f)

    /** Persists the current page + zoom so the reader reopens exactly where it was left off. */
    fun onPageChanged(page: Int, zoom: Float) {
        _uiState.value = _uiState.value.copy(currentPage = page)
        currentPageFlow.value = page
        viewModelScope.launch { libraryRepository.updateReadingPosition(paperId, page, zoom) }
    }

    /** Same persistence as [onPageChanged], without touching the (already-correct) current-page state — used when leaving the reader to also capture a zoom change made without scrolling to a new page. */
    fun saveReadingState(page: Int, zoom: Float) {
        viewModelScope.launch { libraryRepository.updateReadingPosition(paperId, page, zoom) }
    }

    fun jumpToPage(page: Int) {
        _uiState.value = _uiState.value.copy(jumpToPage = page)
    }

    fun consumeJumpToPage() {
        _uiState.value = _uiState.value.copy(jumpToPage = null)
    }

    fun setMode(mode: ReaderMode) {
        _uiState.value = _uiState.value.copy(mode = mode)
    }

    fun setMarkerColor(color: MarkerColor) {
        _uiState.value = _uiState.value.copy(markerColor = color)
    }

    fun setMarkerThickness(thickness: MarkerThickness) {
        _uiState.value = _uiState.value.copy(markerThickness = thickness)
    }

    /** Only the single most recent annotation is undoable, cleared once anything else happens. */
    private var lastCreatedAnnotationId: Long? = null

    private fun rememberForUndo(id: Long) {
        lastCreatedAnnotationId = id
        _uiState.value = _uiState.value.copy(canUndoAnnotation = true)
    }

    fun undoLastAnnotation() {
        val id = lastCreatedAnnotationId ?: return
        lastCreatedAnnotationId = null
        _uiState.value = _uiState.value.copy(canUndoAnnotation = false)
        viewModelScope.launch { annotationRepository.deleteById(id) }
    }

    fun addHighlight(page: Int, rect: NormalizedRect, color: Int) {
        viewModelScope.launch {
            rememberForUndo(annotationRepository.addHighlight(paperId, page, listOf(rect), color))
        }
    }

    fun addNote(page: Int, anchor: NormalizedRect, color: Int, text: String) {
        viewModelScope.launch {
            rememberForUndo(annotationRepository.addNote(paperId, page, anchor, color, text))
        }
    }

    fun addDrawing(page: Int, points: List<NormalizedRect>) {
        val state = _uiState.value
        viewModelScope.launch {
            rememberForUndo(
                annotationRepository.addDrawing(paperId, page, points, state.markerColor.argb, state.markerThickness.px)
            )
        }
    }

    fun deleteAnnotation(annotation: Annotation) {
        if (annotation.id == lastCreatedAnnotationId) {
            lastCreatedAnnotationId = null
            _uiState.value = _uiState.value.copy(canUndoAnnotation = false)
        }
        viewModelScope.launch { annotationRepository.delete(annotation) }
    }

    fun updateNoteText(annotation: Annotation, text: String) {
        viewModelScope.launch { annotationRepository.updateNoteText(annotation, text) }
    }

    /** Resolves a tapped reference to its URL; the caller launches it in the system browser. */
    fun openReference(reference: ParsedReference, onResolved: (url: String) -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(resolvingReference = true)
            val target = referenceRepository.resolveTarget(reference.text)
            _uiState.value = _uiState.value.copy(resolvingReference = false)
            onResolved(target.url)
        }
    }

    /**
     * An inline "[12]" (or "[3, 7]", "[4-6]", or author-year "(Vaswani et al., 2017)") tap
     * resolves every referenced entry, like Scholar PDF Reader — a citation dialog decides
     * whether to show one reference directly or a list to choose from, based on how many keys
     * this marker actually grouped together.
     */
    fun referencesForCitation(citation: InlineCitation): List<ParsedReference> =
        citation.keys.mapNotNull { key ->
            when (key) {
                is CitationKey.Numbered -> _uiState.value.references.getOrNull(key.index - 1)
                is CitationKey.AuthorYear -> _uiState.value.references.firstOrNull { AuthorYearMatcher.matches(it.text, key) }
                is CitationKey.Coded -> _uiState.value.references.firstOrNull { CodedMatcher.matches(it.text, key.code) }
            }
        }

    fun dismissLibraryMessage() {
        _uiState.value = _uiState.value.copy(libraryMessage = null)
    }

    /** Consumes the one-shot [ReaderUiState.openPaperId] navigation event once handled. */
    fun consumeOpenPaperId() {
        _uiState.value = _uiState.value.copy(openPaperId = null)
    }

    /**
     * One-click "download this reference straight into the library, then open it" — only
     * succeeds when the resolved link is (or can be turned into, e.g. an arXiv /abs/ page, or
     * found via an arXiv title search as a fallback for paywalled DOI landing pages) a directly
     * downloadable open-access PDF. Deliberately does *not* open a browser tab on failure — the
     * user opted into a silent download, not a navigation, so a failure just reports why via
     * [ReaderUiState.libraryMessage]; they can still tap "Open" separately if they want the page.
     */
    fun downloadReferenceToLibrary(reference: ParsedReference) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(downloadingReferenceIndex = reference.index)
            val target = referenceRepository.resolveTarget(reference.text)
            val bytes = referenceRepository.tryDownloadOpenAccessPdf(
                url = target.url,
                doi = target.doi,
                fallbackTitle = target.resolvedTitle ?: ReferenceTitleGuesser.guessTitle(reference.text).take(200),
            )
            _uiState.value = if (bytes != null) {
                val result = libraryRepository.importFromBytes(
                    bytes,
                    suggestedFallbackName = target.resolvedTitle ?: reference.text.take(80),
                    sourceUrl = target.url,
                )
                _uiState.value.copy(
                    downloadingReferenceIndex = null,
                    libraryMessage = result.fold(
                        onSuccess = { "Saved to library" },
                        onFailure = { "Download succeeded but import failed: ${it.message}" },
                    ),
                    openPaperId = result.getOrNull(),
                )
            } else {
                _uiState.value.copy(
                    downloadingReferenceIndex = null,
                    libraryMessage = "No open-access PDF found for this reference — tap Open to view it online instead",
                )
            }
        }
    }

    // --- Search -------------------------------------------------------------------------

    fun toggleSearch(active: Boolean) {
        _searchState.value = _searchState.value.copy(active = active)
        if (!active) clearSearch()
    }

    fun setSearchOptions(caseSensitive: Boolean = _searchState.value.caseSensitive, wholeWord: Boolean = _searchState.value.wholeWord) {
        _searchState.value = _searchState.value.copy(caseSensitive = caseSensitive, wholeWord = wholeWord)
        runSearch(_searchState.value.query)
    }

    fun runSearch(query: String) {
        _searchState.value = _searchState.value.copy(query = query)
        if (query.isBlank()) {
            _searchState.value = _searchState.value.copy(matches = emptyList(), currentMatchIndex = -1, loading = false)
            return
        }
        val currentFile = file ?: return
        viewModelScope.launch {
            _searchState.value = _searchState.value.copy(loading = true)
            // Shares the same cache inline citations use; only re-extracts if search is
            // triggered before the initial load (kicked off in open()) has finished.
            val wordsByPage = _pageWords.value.ifEmpty {
                withContext(Dispatchers.Default) { PdfWordExtractor.extractAllPages(currentFile) }
                    .also { _pageWords.value = it }
            }

            val options = _searchState.value
            val results = withContext(Dispatchers.Default) {
                wordsByPage.entries.sortedBy { it.key }.flatMap { (page, words) ->
                    DocumentSearchMatcher.findMatches(words, query, options.caseSensitive, options.wholeWord)
                        .map { PageSearchMatch(page, it) }
                }
            }
            _searchState.value = _searchState.value.copy(
                matches = results,
                currentMatchIndex = if (results.isEmpty()) -1 else 0,
                loading = false,
            )
            results.firstOrNull()?.let { jumpToPage(it.page) }
        }
    }

    fun nextSearchMatch() = moveSearchMatch(1)
    fun previousSearchMatch() = moveSearchMatch(-1)

    private fun moveSearchMatch(delta: Int) {
        val state = _searchState.value
        if (state.matches.isEmpty()) return
        val next = (state.currentMatchIndex + delta + state.matches.size) % state.matches.size
        _searchState.value = state.copy(currentMatchIndex = next)
        jumpToPage(state.matches[next].page)
    }

    private fun clearSearch() {
        _searchState.value = SearchState()
    }

    override fun onCleared() {
        super.onCleared()
        renderer?.close()
    }
}
