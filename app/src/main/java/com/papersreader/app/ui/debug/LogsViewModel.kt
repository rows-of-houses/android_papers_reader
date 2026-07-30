package com.papersreader.app.ui.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papersreader.app.logging.FileLogTree
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class LogsUiState(
    val logText: String = "",
    val lastCrashText: String? = null,
    val loading: Boolean = true,
)

@HiltViewModel
class LogsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileLogTree: FileLogTree,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LogsUiState())
    val uiState: StateFlow<LogsUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            val (logs, crash) = withContext(Dispatchers.IO) {
                fileLogTree.readAll() to readLastCrash()
            }
            _uiState.value = LogsUiState(logText = logs, lastCrashText = crash, loading = false)
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { fileLogTree.clear() }
            refresh()
        }
    }

    fun copyLogsToClipboard() {
        copyToClipboard("Papers Reader logs", _uiState.value.logText)
    }

    fun copyLastCrashToClipboard() {
        _uiState.value.lastCrashText?.let { copyToClipboard("Papers Reader crash", it) }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun readLastCrash(): String? =
        File(context.filesDir, "logs/last_crash.log").takeIf { it.exists() }?.readText()
}
