package com.papersreader.app.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.papersreader.app.data.db.BrowserTabEntity

@Composable
fun BrowserScreen(
    onSaveToLibrary: () -> Unit,
    viewModel: BrowserViewModel = hiltViewModel(),
) {
    val tabs by viewModel.tabs.collectAsState()
    val saveState by viewModel.saveState.collectAsState()
    val webViews = remember { mutableStateMapOf<Long, WebView>() }

    LaunchedEffect(Unit) { viewModel.ensureAtLeastOneTab() }

    val activeTab = tabs.firstOrNull { it.isActive } ?: tabs.firstOrNull()

    LaunchedEffect(saveState) {
        if (saveState is SaveToLibraryState.Done) {
            onSaveToLibrary()
            viewModel.dismissSaveState()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabStrip(
            tabs = tabs,
            activeTabId = activeTab?.id,
            onTabSelected = { viewModel.setActive(it) },
            onTabClosed = { viewModel.closeTab(it) },
            onNewTab = { viewModel.newTab() },
        )

        if (activeTab != null) {
            AddressBar(
                url = activeTab.url,
                onNavigate = { newUrl -> webViews[activeTab.id]?.loadUrl(normalizeUrlOrSearch(newUrl)) },
                onBack = { webViews[activeTab.id]?.let { if (it.canGoBack()) it.goBack() } },
                onForward = { webViews[activeTab.id]?.let { if (it.canGoForward()) it.goForward() } },
                onReload = { webViews[activeTab.id]?.reload() },
                onSave = {
                    val webView = webViews[activeTab.id]
                    val currentUrl = webView?.url ?: activeTab.url
                    val cookies = CookieManager.getInstance().getCookie(currentUrl)
                    viewModel.savePdfToLibrary(currentUrl, cookies)
                },
                isSaving = saveState is SaveToLibraryState.Saving,
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            tabs.forEach { tab ->
                key(tab.id) {
                    WebViewTabContent(
                        tab = tab,
                        isActive = tab.id == activeTab?.id,
                        onWebViewReady = { webViews[tab.id] = it },
                        onMetaChanged = { url, title -> viewModel.updateTabMeta(tab, url, title) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TabStrip(
    tabs: List<BrowserTabEntity>,
    activeTabId: Long?,
    onTabSelected: (BrowserTabEntity) -> Unit,
    onTabClosed: (BrowserTabEntity) -> Unit,
    onNewTab: () -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 4.dp, horizontal = 4.dp),
    ) {
        items(tabs, key = { it.id }) { tab ->
            val isActive = tab.id == activeTabId
            Surface(
                color = if (isActive) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .widthIn(max = 160.dp),
                onClick = { onTabSelected(tab) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        text = tab.title?.takeIf { it.isNotBlank() } ?: tab.url,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onTabClosed(tab) }, modifier = Modifier.padding(start = 4.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Close tab", modifier = Modifier.widthIn(max = 16.dp))
                    }
                }
            }
        }
        item {
            IconButton(onClick = onNewTab) {
                Icon(Icons.Filled.Add, contentDescription = "New tab")
            }
        }
    }
}

@Composable
private fun AddressBar(
    url: String,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onSave: () -> Unit,
    isSaving: Boolean,
) {
    var text by remember(url) { mutableStateOf(url) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
        IconButton(onClick = onForward) { Icon(Icons.Filled.ArrowForward, contentDescription = "Forward") }
        IconButton(onClick = onReload) { Icon(Icons.Filled.Refresh, contentDescription = "Reload") }
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Go,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { onNavigate(text) }),
        )
        if (isSaving) {
            CircularProgressIndicator(modifier = Modifier.padding(8.dp))
        } else {
            IconButton(onClick = onSave) { Icon(Icons.Filled.SaveAlt, contentDescription = "Save PDF to library") }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewTabContent(
    tab: BrowserTabEntity,
    isActive: Boolean,
    onWebViewReady: (WebView) -> Unit,
    onMetaChanged: (url: String, title: String?) -> Unit,
) {
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        onMetaChanged(url, view.title)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        onMetaChanged(url, view.title)
                    }
                }
                loadUrl(tab.url)
                onWebViewReady(this)
            }
        },
        update = { webView ->
            webView.visibility = if (isActive) View.VISIBLE else View.GONE
        },
    )
}

private fun normalizeUrlOrSearch(input: String): String {
    val trimmed = input.trim()
    val looksLikeUrl = trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
        (trimmed.contains(".") && !trimmed.contains(" "))
    return when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        looksLikeUrl -> "https://$trimmed"
        else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
    }
}
