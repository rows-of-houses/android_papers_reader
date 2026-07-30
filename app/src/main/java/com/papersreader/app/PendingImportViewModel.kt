package com.papersreader.app

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Carries a PDF Uri handed to the app via VIEW/SEND intent until the Library screen imports it. */
@HiltViewModel
class PendingImportViewModel @Inject constructor() : ViewModel() {
    private val _pendingImportUri = MutableStateFlow<String?>(null)
    val pendingImportUri: StateFlow<String?> = _pendingImportUri

    fun setPendingImport(uri: String) {
        _pendingImportUri.value = uri
    }

    fun consume() {
        _pendingImportUri.value = null
    }
}
