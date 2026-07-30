package com.papersreader.app.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papersreader.app.data.db.BrowserTabEntity
import com.papersreader.app.data.repository.BrowserTabRepository
import com.papersreader.app.data.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import javax.inject.Inject

sealed interface SaveToLibraryState {
    data object Idle : SaveToLibraryState
    data object Saving : SaveToLibraryState
    data class Done(val title: String) : SaveToLibraryState
    data class Error(val message: String) : SaveToLibraryState
}

private const val NEW_TAB_URL = "https://scholar.google.com/"

@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val browserTabRepository: BrowserTabRepository,
    private val libraryRepository: LibraryRepository,
    private val httpClient: OkHttpClient,
) : ViewModel() {

    val tabs: StateFlow<List<BrowserTabEntity>> = browserTabRepository.observeTabs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _saveState = MutableStateFlow<SaveToLibraryState>(SaveToLibraryState.Idle)
    val saveState: StateFlow<SaveToLibraryState> = _saveState

    fun ensureAtLeastOneTab() {
        viewModelScope.launch { browserTabRepository.ensureAtLeastOneTab(NEW_TAB_URL, "New tab") }
    }

    fun newTab(url: String = NEW_TAB_URL) {
        viewModelScope.launch { browserTabRepository.openNewTab(url, null) }
    }

    fun setActive(tab: BrowserTabEntity) {
        viewModelScope.launch { browserTabRepository.setActive(tab.id) }
    }

    fun closeTab(tab: BrowserTabEntity) {
        viewModelScope.launch { browserTabRepository.closeTab(tab, NEW_TAB_URL, "New tab") }
    }

    fun updateTabMeta(tab: BrowserTabEntity, url: String, title: String?) {
        if (tab.url == url && tab.title == title) return
        viewModelScope.launch { browserTabRepository.updateTab(tab.copy(url = url, title = title)) }
    }

    /**
     * Downloads whatever PDF the user is currently looking at and imports it into the library
     * under its real title, carrying over WebView's session cookies so it works for
     * Cloudflare/paywall-gated publisher pages the user already passed the challenge for.
     */
    fun savePdfToLibrary(url: String, cookieHeader: String?) {
        viewModelScope.launch {
            _saveState.value = SaveToLibraryState.Saving
            val result = runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", MOBILE_USER_AGENT)
                    .apply { if (!cookieHeader.isNullOrBlank()) header("Cookie", cookieHeader) }
                    .build()
                val bytes = withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("HTTP ${response.code}")
                        response.body?.bytes() ?: error("Empty response body")
                    }
                }
                libraryRepository.importFromBytes(bytes, suggestedFallbackName = "Downloaded paper", sourceUrl = url).getOrThrow()
            }
            _saveState.value = result.fold(
                onSuccess = { SaveToLibraryState.Done("Saved to library") },
                onFailure = { e ->
                    Timber.e(e, "Save to library failed for $url")
                    SaveToLibraryState.Error(e.message ?: "Could not save this page as a PDF")
                },
            )
        }
    }

    fun dismissSaveState() {
        _saveState.value = SaveToLibraryState.Idle
    }

    companion object {
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36"
    }
}
