package com.papersreader.app.ui.reader

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papersreader.app.data.pdf.ParsedReference
import com.papersreader.app.data.pdf.PdfPageRenderer
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
import javax.inject.Inject

enum class ReaderMode { VIEW, HIGHLIGHT, NOTE }

data class ReaderUiState(
    val title: String = "",
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val mode: ReaderMode = ReaderMode.VIEW,
    val references: List<ParsedReference> = emptyList(),
    val referencesLoading: Boolean = false,
    val resolvingReference: Boolean = false,
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
    private var paperId: Long = -1

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState

    private val currentPageFlow = MutableStateFlow(0)

    val currentPageAnnotations: StateFlow<List<Annotation>> = currentPageFlow.flatMapLatest { page ->
        annotationRepository.observeForPage(paperId, page)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun open(paperId: Long) {
        if (this.paperId == paperId) return
        this.paperId = paperId
        viewModelScope.launch {
            val paper = libraryRepository.getPaper(paperId) ?: run {
                _uiState.value = _uiState.value.copy(error = "Paper not found")
                return@launch
            }
            _uiState.value = _uiState.value.copy(title = paper.title, currentPage = paper.lastPage)
            currentPageFlow.value = paper.lastPage

            val file = libraryRepository.paperFile(paper)
            val newRenderer = withContext(Dispatchers.IO) { PdfPageRenderer(file) }
            renderer = newRenderer
            _uiState.value = _uiState.value.copy(pageCount = newRenderer.pageCount)

            loadReferences(file)
        }
    }

    private fun loadReferences(file: java.io.File) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(referencesLoading = true)
            val refs = withContext(Dispatchers.Default) { referenceRepository.parseReferences(file) }
            _uiState.value = _uiState.value.copy(references = refs, referencesLoading = false)
        }
    }

    suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap? =
        runCatching { renderer?.renderPage(pageIndex, targetWidthPx) }
            .onFailure { Timber.e(it, "Failed to render page $pageIndex") }
            .getOrNull()

    fun onPageChanged(page: Int) {
        _uiState.value = _uiState.value.copy(currentPage = page)
        currentPageFlow.value = page
        viewModelScope.launch { libraryRepository.updateReadingPosition(paperId, page) }
    }

    fun setMode(mode: ReaderMode) {
        _uiState.value = _uiState.value.copy(mode = mode)
    }

    fun addHighlight(rect: NormalizedRect, color: Int) {
        viewModelScope.launch {
            annotationRepository.addHighlight(paperId, currentPageFlow.value, listOf(rect), color)
        }
    }

    fun addNote(anchor: NormalizedRect, color: Int, text: String) {
        viewModelScope.launch {
            annotationRepository.addNote(paperId, currentPageFlow.value, anchor, color, text)
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

    override fun onCleared() {
        super.onCleared()
        renderer?.close()
    }
}
