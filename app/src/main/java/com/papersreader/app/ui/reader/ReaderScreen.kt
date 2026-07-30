package com.papersreader.app.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.papersreader.app.data.pdf.ParsedReference
import com.papersreader.app.data.repository.Annotation
import com.papersreader.app.data.repository.NormalizedRect
import kotlinx.coroutines.launch

private val highlightColor = androidx.compose.ui.graphics.Color(0xFFFFEB3B).toArgb()
private val noteColor = androidx.compose.ui.graphics.Color(0xFF2196F3).toArgb()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    paperId: Long,
    onBack: () -> Unit,
    onOpenInBrowser: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    LaunchedEffect(paperId) { viewModel.open(paperId) }

    val uiState by viewModel.uiState.collectAsState()
    val pageAnnotations by viewModel.currentPageAnnotations.collectAsState()
    var showReferences by remember { mutableStateOf(false) }
    var noteDialogAnchor by remember { mutableStateOf<NormalizedRect?>(null) }
    var inspectedAnnotation by remember { mutableStateOf<Annotation?>(null) }

    val pagerState = rememberPagerState(initialPage = uiState.currentPage) { uiState.pageCount.coerceAtLeast(1) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pagerState.currentPage) {
        if (uiState.pageCount > 0) viewModel.onPageChanged(pagerState.currentPage)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(uiState.title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        if (uiState.pageCount > 0) {
                            Text(
                                "${pagerState.currentPage + 1} / ${uiState.pageCount}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconToggleButton(
                        checked = uiState.mode == ReaderMode.HIGHLIGHT,
                        onCheckedChange = { checked ->
                            viewModel.setMode(if (checked) ReaderMode.HIGHLIGHT else ReaderMode.VIEW)
                        },
                    ) {
                        Icon(Icons.Filled.FormatColorText, contentDescription = "Highlight mode")
                    }
                    IconToggleButton(
                        checked = uiState.mode == ReaderMode.NOTE,
                        onCheckedChange = { checked ->
                            viewModel.setMode(if (checked) ReaderMode.NOTE else ReaderMode.VIEW)
                        },
                    ) {
                        Icon(Icons.Filled.NoteAdd, contentDescription = "Note mode")
                    }
                    IconButton(onClick = { showReferences = true }) {
                        Icon(Icons.Filled.LibraryBooks, contentDescription = "References")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (uiState.pageCount == 0) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = uiState.mode == ReaderMode.VIEW,
                    modifier = Modifier.fillMaxSize(),
                ) { pageIndex ->
                    PageContent(
                        pageIndex = pageIndex,
                        mode = uiState.mode,
                        annotations = if (pageIndex == pagerState.currentPage) pageAnnotations else emptyList(),
                        renderPage = viewModel::renderPage,
                        onHighlightCreated = { rect -> viewModel.addHighlight(rect, highlightColor) },
                        onNoteRequested = { anchor -> noteDialogAnchor = anchor },
                        onAnnotationTapped = { annotation -> inspectedAnnotation = annotation },
                    )
                }

                if (uiState.mode != ReaderMode.VIEW) {
                    PageNavArrows(
                        canGoBack = pagerState.currentPage > 0,
                        canGoForward = pagerState.currentPage < uiState.pageCount - 1,
                        onPrev = { scope.launch { pagerState.scrollToPage(pagerState.currentPage - 1) } },
                        onNext = { scope.launch { pagerState.scrollToPage(pagerState.currentPage + 1) } },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    )
                }
            }

            if (uiState.resolvingReference) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }

    if (showReferences) {
        ReferencesSheet(
            references = uiState.references,
            loading = uiState.referencesLoading,
            onReferenceClick = { ref ->
                showReferences = false
                viewModel.openReference(ref, onOpened = onOpenInBrowser)
            },
            onDismiss = { showReferences = false },
        )
    }

    noteDialogAnchor?.let { anchor ->
        NoteInputDialog(
            onConfirm = { text ->
                viewModel.addNote(anchor, noteColor, text)
                noteDialogAnchor = null
            },
            onDismiss = { noteDialogAnchor = null },
        )
    }

    inspectedAnnotation?.let { annotation ->
        AnnotationInspectDialog(
            annotation = annotation,
            onDelete = {
                viewModel.deleteAnnotation(annotation)
                inspectedAnnotation = null
            },
            onDismiss = { inspectedAnnotation = null },
        )
    }
}

@Composable
private fun PageContent(
    pageIndex: Int,
    mode: ReaderMode,
    annotations: List<Annotation>,
    renderPage: suspend (Int, Int) -> Bitmap?,
    onHighlightCreated: (NormalizedRect) -> Unit,
    onNoteRequested: (NormalizedRect) -> Unit,
    onAnnotationTapped: (Annotation) -> Unit,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var bitmap by remember(pageIndex, containerSize) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(pageIndex, containerSize) {
        if (containerSize.width > 0) {
            bitmap = renderPage(pageIndex, containerSize.width)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it },
    ) {
        val currentBitmap = bitmap
        if (currentBitmap == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1}",
                modifier = Modifier.fillMaxWidth(),
            )
            val density = LocalDensity.current
            AnnotationOverlay(
                modifier = Modifier.size(
                    width = with(density) { currentBitmap.width.toFloat().toDp() },
                    height = with(density) { currentBitmap.height.toFloat().toDp() },
                ),
                mode = mode,
                annotations = annotations,
                pageSizePx = IntSize(currentBitmap.width, currentBitmap.height),
                onHighlightCreated = onHighlightCreated,
                onNoteRequested = onNoteRequested,
                onAnnotationTapped = onAnnotationTapped,
            )
        }
    }
}

@Composable
private fun PageNavArrows(
    canGoBack: Boolean,
    canGoForward: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        TextButton(onClick = onPrev, enabled = canGoBack) { Text("◀ Prev") }
        TextButton(onClick = onNext, enabled = canGoForward) { Text("Next ▶") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReferencesSheet(
    references: List<ParsedReference>,
    loading: Boolean,
    onReferenceClick: (ParsedReference) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("References", style = MaterialTheme.typography.titleLarge)
            Text(
                "Tap a reference to look it up and open it in a new browser tab.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                references.isEmpty() -> Text("No reference list detected in this PDF.")
                else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    items(references, key = { it.index }) { ref ->
                        Text(
                            "${ref.index}. ${ref.text}",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onReferenceClick(ref) }
                                .padding(vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteInputDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add note") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("Your note…") })
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AnnotationInspectDialog(annotation: Annotation, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (annotation.type == com.papersreader.app.data.db.AnnotationType.NOTE) "Note" else "Highlight") },
        text = { Text(annotation.note ?: "(no text)") },
        confirmButton = {
            TextButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text("Delete")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
