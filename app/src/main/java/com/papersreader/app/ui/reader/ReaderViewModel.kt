package com.papersreader.app.ui.reader

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papersreader.app.data.pdf.DocumentSearchMatcher
import com.papersreader.app.data.pdf.InlineCitation
import com.papersreader.app.data.pdf.OutlineEntry
import com.papersreader.app.data.pdf.ParsedReference
import com.papersreader.app.data.pdf.PdfOutlineExtractor
import com.papersreader.app.data.pdf.PdfPageRenderer
import com.papersreader.app.data.pdf.PdfWord
import com.papersreader.app.data.pdf.PdfWordExtractor
import com.papersreader.app.data.pdf.SearchMatch
import com.papersreader.app.data.repository.Annotation
import com.papersreader.app.data.repository.AnnotationRepository
import com.papersreader.app.data.repository.BrowserTabRepository
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

data class ReaderUiState(
    val title: String = "",
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val mode: ReaderMode = ReaderMode.VIEW,
    val references: List<ParsedReference> = emptyList(),
    val referencesLoading: Boolean = false,
    val resolvingReference: Boolean = false,
    val outline: List<OutlineEntry> = emptyList(),
    val zoom: Float = 1f,
    val jumpToPage: Int? = null,
    val error: String? = null,
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val annotationRepository: AnnotationRepository,
    private val referenceRepository: ReferenceRepository,
    private val browserTabRepository: BrowserTabRepository,
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

    /** Per-page word cache (for inline citations); populated lazily as pages become visible. */
    private val pageWordsCache = HashMap<Int, List<PdfWord>>()
    private val pageWordsRequested = HashSet<Int>()
    private val _pageWords = MutableStateFlow<Map<Int, List<PdfWord>>>(emptyMap())
    val pageWords: StateFlow<Map<Int, List<PdfWord>>> = _pageWords

    /** Search runs across the whole document, so this cache is separate and filled once. */
    private var allPageWordsCache: Map<Int, List<PdfWord>>? = null

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
            _uiState.value = _uiState.value.copy(title = paper.title, currentPage = paper.lastPage)
            currentPageFlow.value = paper.lastPage

            val paperFile = libraryRepository.paperFile(paper)
            file = paperFile
            val newRenderer = withContext(Dispatchers.IO) { PdfPageRenderer(paperFile) }
            renderer = newRenderer
            _uiState.value = _uiState.value.copy(pageCount = newRenderer.pageCount)

            loadReferences(paperFile)
            loadOutline(paperFile)
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

    /** Lazily extracts and caches word positions for [pageIndex], used for inline citations. */
    fun ensurePageWordsLoaded(pageIndex: Int) {
        if (!pageWordsRequested.add(pageIndex)) return
        val currentFile = file ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val words = runCatching { PdfWordExtractor.extractWords(currentFile, pageIndex) }.getOrDefault(emptyList())
            pageWordsCache[pageIndex] = words
            _pageWords.value = pageWordsCache.toMap()
        }
    }

    fun onPageChanged(page: Int) {
        _uiState.value = _uiState.value.copy(currentPage = page)
        currentPageFlow.value = page
        viewModelScope.launch { libraryRepository.updateReadingPosition(paperId, page) }
    }

    fun setZoom(zoom: Float) {
        _uiState.value = _uiState.value.copy(zoom = zoom.coerceIn(1f, 5f))
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

    fun addHighlight(page: Int, rect: NormalizedRect, color: Int) {
        viewModelScope.launch {
            annotationRepository.addHighlight(paperId, page, listOf(rect), color)
        }
    }

    fun addNote(page: Int, anchor: NormalizedRect, color: Int, text: String) {
        viewModelScope.launch {
            annotationRepository.addNote(paperId, page, anchor, color, text)
        }
    }

    fun addDrawing(page: Int, points: List<NormalizedRect>, color: Int) {
        viewModelScope.launch {
            annotationRepository.addDrawing(paperId, page, points, color)
        }
    }

    fun deleteAnnotation(annotation: Annotation) {
        viewModelScope.launch { annotationRepository.delete(annotation) }
    }

    fun updateNoteText(annotation: Annotation, text: String) {
        viewModelScope.launch { annotationRepository.updateNoteText(annotation, text) }
    }

    /** Resolves a tapped reference and opens it as a new browser tab; returns true once opened. */
    fun openReference(reference: ParsedReference, onOpened: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(resolvingReference = true)
            val target = referenceRepository.resolveTarget(reference.text)
            browserTabRepository.openNewTab(target.url, target.resolvedTitle)
            _uiState.value = _uiState.value.copy(resolvingReference = false)
            onOpened()
        }
    }

    /** An inline "[12]" tap jumps to (and briefly opens) the matching reference, like Scholar PDF Reader. */
    fun referenceForCitation(citation: InlineCitation): ParsedReference? {
        val index = citation.referenceIndices.firstOrNull() ?: return null
        return _uiState.value.references.getOrNull(index - 1)
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
            val wordsByPage = allPageWordsCache ?: withContext(Dispatchers.Default) {
                PdfWordExtractor.extractAllPages(currentFile)
            }.also { allPageWordsCache = it }

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
