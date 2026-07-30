package com.papersreader.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papersreader.app.data.db.PaperEntity
import com.papersreader.app.data.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

sealed interface ImportState {
    data object Idle : ImportState
    data object Importing : ImportState
    data class Error(val message: String) : ImportState
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    val papers: StateFlow<List<PaperEntity>> = libraryRepository.observePapers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState

    fun importFromUri(uriString: String) {
        viewModelScope.launch {
            _importState.value = ImportState.Importing
            val result = libraryRepository.importFromUri(uriString, suggestedFallbackName = "Untitled paper")
            _importState.value = result.fold(
                onSuccess = { ImportState.Idle },
                onFailure = { e ->
                    Timber.e(e, "Import failed")
                    ImportState.Error(e.message ?: "Import failed")
                },
            )
        }
    }

    fun deletePaper(paper: PaperEntity) {
        viewModelScope.launch { libraryRepository.deletePaper(paper) }
    }

    fun dismissError() {
        _importState.value = ImportState.Idle
    }
}
