package com.papersreader.app.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.papersreader.app.data.db.AnnotationType
import com.papersreader.app.data.pdf.InlineCitation
import com.papersreader.app.data.pdf.InlineCitationDetector
import com.papersreader.app.data.pdf.OutlineEntry
import com.papersreader.app.data.pdf.ParsedReference
import com.papersreader.app.data.pdf.PdfWord
import com.papersreader.app.data.repository.Annotation
import com.papersreader.app.data.repository.NormalizedRect
import kotlinx.coroutines.launch

private val highlightColor = androidx.compose.ui.graphics.Color(0xFFFFEB3B).toArgb()
private val noteColor = androidx.compose.ui.graphics.Color(0xFF2196F3).toArgb()
private val drawColor = androidx.compose.ui.graphics.Color(0xFFE53935).toArgb()

/** Render pages a bit sharper than the screen so pinch-zoom stays reasonably crisp. */
private const val RENDER_SCALE_FACTOR = 2

private data class PendingNote(val page: Int, val anchor: NormalizedRect)

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
    val paperAnnotations by viewModel.paperAnnotations.collectAsState()
    val annotationsByPage = remember(paperAnnotations) { paperAnnotations.groupBy { it.page } }
    val pageWords by viewModel.pageWords.collectAsState()
    val searchState by viewModel.searchState.collectAsState()

    var showReferences by remember { mutableStateOf(false) }
    var showOutline by remember { mutableStateOf(false) }
    var noteDialogAnchor by remember { mutableStateOf<PendingNote?>(null) }
    var inspectedAnnotation by remember { mutableStateOf<Annotation?>(null) }
    var inspectedCitation by remember { mutableStateOf<InlineCitation?>(null) }

    val listState = rememberLazyListState()

    var zoom by remember { mutableStateOf(1f) }
    var panX by remember { mutableStateOf(0f) }

    // Track which page is mostly at the top of the viewport as the "current" page.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { page -> if (page != uiState.currentPage) viewModel.onPageChanged(page) }
    }

    LaunchedEffect(uiState.jumpToPage) {
        uiState.jumpToPage?.let { page ->
            zoom = 1f
            panX = 0f
            listState.animateScrollToItem(page)
            viewModel.consumeJumpToPage()
        }
    }

    val activeSearchHighlight = searchState.matches.getOrNull(searchState.currentMatchIndex)?.match?.words?.firstOrNull()?.rect

    Scaffold(
        topBar = {
            if (searchState.active) {
                SearchBar(
                    searchState = searchState,
                    onQueryChange = viewModel::runSearch,
                    onToggleCaseSensitive = { viewModel.setSearchOptions(caseSensitive = !searchState.caseSensitive) },
                    onToggleWholeWord = { viewModel.setSearchOptions(wholeWord = !searchState.wholeWord) },
                    onNext = viewModel::nextSearchMatch,
                    onPrevious = viewModel::previousSearchMatch,
                    onClose = { viewModel.toggleSearch(false) },
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(uiState.title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                            if (uiState.pageCount > 0) {
                                Text(
                                    "${uiState.currentPage + 1} / ${uiState.pageCount}",
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
                        IconButton(onClick = { viewModel.toggleSearch(true) }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search in document")
                        }
                        if (uiState.outline.isNotEmpty()) {
                            IconButton(onClick = { showOutline = true }) {
                                Icon(Icons.Filled.List, contentDescription = "Table of contents")
                            }
                        }
                        IconButton(onClick = { showReferences = true }) {
                            Icon(Icons.Filled.LibraryBooks, contentDescription = "References")
                        }
                    },
                )
            }
        },
        bottomBar = {
            AnnotationModeBar(mode = uiState.mode, onModeSelected = viewModel::setMode)
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (uiState.pageCount == 0) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    state = listState,
                    userScrollEnabled = uiState.mode == ReaderMode.VIEW,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(uiState.mode) {
                            if (uiState.mode != ReaderMode.VIEW) return@pointerInput
                            detectPinchZoom { pan, gestureZoom ->
                                zoom = (zoom * gestureZoom).coerceIn(1f, 5f)
                                panX = if (zoom <= 1f) 0f else panX + pan.x
                            }
                        }
                        .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = panX),
                ) {
                    items(uiState.pageCount, key = { it }) { pageIndex ->
                        PageContent(
                            pageIndex = pageIndex,
                            mode = uiState.mode,
                            annotations = annotationsByPage[pageIndex] ?: emptyList(),
                            words = pageWords[pageIndex] ?: emptyList(),
                            searchHighlights = searchState.matches
                                .filter { it.page == pageIndex }
                                .flatMap { it.match.words.map(PdfWord::rect) },
                            activeSearchHighlight = if (searchState.matches.getOrNull(searchState.currentMatchIndex)?.page == pageIndex) activeSearchHighlight else null,
                            renderPage = viewModel::renderPage,
                            pageAspectRatio = viewModel::pageAspectRatio,
                            onWordsNeeded = { viewModel.ensurePageWordsLoaded(pageIndex) },
                            onHighlightCreated = { rect -> viewModel.addHighlight(pageIndex, rect, highlightColor) },
                            onNoteRequested = { anchor -> noteDialogAnchor = PendingNote(pageIndex, anchor) },
                            onDrawingCreated = { points -> viewModel.addDrawing(pageIndex, points, drawColor) },
                            onAnnotationTapped = { annotation -> inspectedAnnotation = annotation },
                            onCitationTapped = { citation -> inspectedCitation = citation },
                        )
                    }
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

    if (showOutline) {
        OutlineSheet(
            outline = uiState.outline,
            onEntryClick = { entry ->
                showOutline = false
                viewModel.jumpToPage(entry.page)
            },
            onDismiss = { showOutline = false },
        )
    }

    noteDialogAnchor?.let { pending ->
        NoteInputDialog(
            onConfirm = { text ->
                viewModel.addNote(pending.page, pending.anchor, noteColor, text)
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

    inspectedCitation?.let { citation ->
        val reference = viewModel.referenceForCitation(citation)
        CitationDialog(
            citation = citation,
            reference = reference,
            onOpen = {
                inspectedCitation = null
                reference?.let { viewModel.openReference(it, onOpened = onOpenInBrowser) }
            },
            onDismiss = { inspectedCitation = null },
        )
    }
}

@Composable
private fun AnnotationModeBar(mode: ReaderMode, onModeSelected: (ReaderMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        IconToggleButton(
            checked = mode == ReaderMode.HIGHLIGHT,
            onCheckedChange = { onModeSelected(if (it) ReaderMode.HIGHLIGHT else ReaderMode.VIEW) },
        ) { Icon(Icons.Filled.FormatColorText, contentDescription = "Highlight mode") }
        IconToggleButton(
            checked = mode == ReaderMode.NOTE,
            onCheckedChange = { onModeSelected(if (it) ReaderMode.NOTE else ReaderMode.VIEW) },
        ) { Icon(Icons.Filled.NoteAdd, contentDescription = "Note mode") }
        IconToggleButton(
            checked = mode == ReaderMode.DRAW,
            onCheckedChange = { onModeSelected(if (it) ReaderMode.DRAW else ReaderMode.VIEW) },
        ) { Icon(Icons.Filled.Draw, contentDescription = "Draw mode") }
    }
}

@Composable
private fun PageContent(
    pageIndex: Int,
    mode: ReaderMode,
    annotations: List<Annotation>,
    words: List<PdfWord>,
    searchHighlights: List<NormalizedRect>,
    activeSearchHighlight: NormalizedRect?,
    renderPage: suspend (Int, Int) -> Bitmap?,
    pageAspectRatio: suspend (Int) -> Float,
    onWordsNeeded: () -> Unit,
    onHighlightCreated: (NormalizedRect) -> Unit,
    onNoteRequested: (NormalizedRect) -> Unit,
    onDrawingCreated: (List<NormalizedRect>) -> Unit,
    onAnnotationTapped: (Annotation) -> Unit,
    onCitationTapped: (InlineCitation) -> Unit,
) {
    var containerWidth by remember { mutableStateOf(0) }
    var aspectRatio by remember(pageIndex) { mutableStateOf<Float?>(null) }
    var bitmap by remember(pageIndex, containerWidth) { mutableStateOf<Bitmap?>(null) }
    val citations = remember(words) { InlineCitationDetector.detect(words) }

    LaunchedEffect(pageIndex, containerWidth) {
        if (containerWidth <= 0) return@LaunchedEffect
        if (aspectRatio == null) aspectRatio = pageAspectRatio(pageIndex)
        bitmap = renderPage(pageIndex, containerWidth * RENDER_SCALE_FACTOR)
        onWordsNeeded()
    }

    val density = LocalDensity.current
    val displayedHeightPx = aspectRatio?.let { ratio ->
        if (containerWidth <= 0 || ratio <= 0f) null else (containerWidth / ratio).toInt()
    }
    val reservedHeightDp = displayedHeightPx?.let { with(density) { it.toDp() } }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (reservedHeightDp != null) Modifier.heightIn(min = reservedHeightDp) else Modifier)
            .padding(vertical = 4.dp)
            .onSizeChanged { if (it.width > 0) containerWidth = it.width },
    ) {
        val currentBitmap = bitmap
        if (currentBitmap == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            val exactSizeModifier = if (reservedHeightDp != null) {
                Modifier.fillMaxWidth().height(reservedHeightDp)
            } else {
                Modifier.fillMaxWidth()
            }
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1}",
                modifier = exactSizeModifier,
            )
            AnnotationOverlay(
                modifier = exactSizeModifier,
                mode = mode,
                annotations = annotations,
                // The overlay draws in on-screen pixels, which can differ from the bitmap's own
                // (deliberately higher-resolution, see RENDER_SCALE_FACTOR) pixel dimensions.
                pageSizePx = IntSize(containerWidth, displayedHeightPx ?: currentBitmap.height),
                citations = citations,
                searchHighlights = searchHighlights,
                activeSearchHighlight = activeSearchHighlight,
                onHighlightCreated = onHighlightCreated,
                onNoteRequested = onNoteRequested,
                onDrawingCreated = onDrawingCreated,
                onAnnotationTapped = onAnnotationTapped,
                onCitationTapped = onCitationTapped,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    searchState: SearchState,
    onQueryChange: (String) -> Unit,
    onToggleCaseSensitive: () -> Unit,
    onToggleWholeWord: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close search") }
            TextField(
                value = searchState.query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Search in document") },
            )
            if (searchState.loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(horizontal = 8.dp))
            } else if (searchState.query.isNotBlank()) {
                Text(
                    if (searchState.matches.isEmpty()) "0/0" else "${searchState.currentMatchIndex + 1}/${searchState.matches.size}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            IconButton(onClick = onPrevious, enabled = searchState.matches.isNotEmpty()) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous match")
            }
            IconButton(onClick = onNext, enabled = searchState.matches.isNotEmpty()) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match")
            }
        }
        Row(modifier = Modifier.padding(start = 52.dp, bottom = 4.dp)) {
            FilterChipLike(label = "Case-sensitive", selected = searchState.caseSensitive, onClick = onToggleCaseSensitive)
            Spacer(modifier = Modifier.width(8.dp))
            FilterChipLike(label = "Whole word", selected = searchState.wholeWord, onClick = onToggleWholeWord)
        }
    }
}

@Composable
private fun FilterChipLike(label: String, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .background(background, shape = RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = contentColor, style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutlineSheet(outline: List<OutlineEntry>, onEntryClick: (OutlineEntry) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Contents", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                items(outline, key = { it.title + it.page }) { entry ->
                    Text(
                        entry.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEntryClick(entry) }
                            .padding(start = (entry.level * 16).dp, top = 10.dp, bottom = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun CitationDialog(citation: InlineCitation, reference: ParsedReference?, onOpen: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reference ${citation.referenceIndices.joinToString(", ")}") },
        text = { Text(reference?.text ?: "Couldn't find this reference in the bibliography.") },
        confirmButton = {
            TextButton(onClick = onOpen, enabled = reference != null) { Text("Open") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
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
    val titleText = when (annotation.type) {
        AnnotationType.NOTE -> "Note"
        AnnotationType.DRAWING -> "Drawing"
        AnnotationType.HIGHLIGHT -> "Highlight"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titleText) },
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

/**
 * Pinch-to-zoom that only engages once a *second* pointer touches down, unlike Compose's
 * built-in [androidx.compose.foundation.gestures.detectTransformGestures] which starts
 * consuming position changes from a single finger too — that ate every tap on a citation or
 * annotation before it could reach the page's own tap detector underneath, and also fought
 * LazyColumn's own single-finger scroll.
 */
private suspend fun PointerInputScope.detectPinchZoom(onGesture: (pan: Offset, zoom: Float) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            if (event.changes.size >= 2) {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                if (zoomChange != 1f || panChange != Offset.Zero) {
                    onGesture(panChange, zoomChange)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}
