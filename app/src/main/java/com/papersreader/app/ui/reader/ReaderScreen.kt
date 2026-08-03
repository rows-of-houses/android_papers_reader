package com.papersreader.app.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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

/** Edge band (from the left screen edge) that arms the swipe-to-open-outline gesture. */
private val EDGE_SWIPE_BAND = 24.dp
private val EDGE_SWIPE_THRESHOLD = 56.dp

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
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var zoom by remember { mutableStateOf(1f) }
    var panX by remember { mutableStateOf(0f) }

    LaunchedEffect(uiState.libraryMessage) {
        uiState.libraryMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.dismissLibraryMessage()
        }
    }

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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column {
                if (uiState.mode == ReaderMode.DRAW) {
                    MarkerPickerBar(
                        color = uiState.markerColor,
                        thickness = uiState.markerThickness,
                        onColorSelected = viewModel::setMarkerColor,
                        onThicknessSelected = viewModel::setMarkerThickness,
                    )
                }
                AnnotationModeBar(
                    mode = uiState.mode,
                    canUndo = uiState.canUndoAnnotation,
                    onModeSelected = viewModel::setMode,
                    onUndo = viewModel::undoLastAnnotation,
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (uiState.pageCount == 0) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                val view = androidx.compose.ui.platform.LocalView.current
                val edgeDensity = LocalDensity.current
                val edgeSwipeArmed = uiState.mode == ReaderMode.VIEW && uiState.outline.isNotEmpty()
                var columnHeightPx by remember { mutableStateOf(0) }

                // The left-edge swipe lives in exactly the same screen region gesture-nav
                // Android reserves for "swipe from edge to go back", which wins the gesture
                // arbitration by default and eats the touch before our pointerInput ever sees
                // it. Explicitly excluding that strip from system gesture handling is the
                // documented way apps opt back into edge touches (used by drawing/game apps).
                DisposableEffect(edgeSwipeArmed, columnHeightPx) {
                    if (edgeSwipeArmed && columnHeightPx > 0) {
                        val edgeBandPx = with(edgeDensity) { EDGE_SWIPE_BAND.roundToPx() }
                        view.systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, edgeBandPx, columnHeightPx))
                    }
                    onDispose { view.systemGestureExclusionRects = emptyList() }
                }

                LazyColumn(
                    state = listState,
                    userScrollEnabled = uiState.mode == ReaderMode.VIEW,
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { columnHeightPx = it.height }
                        .pointerInput(uiState.mode) {
                            if (uiState.mode != ReaderMode.VIEW) return@pointerInput
                            detectPinchZoom { pan, gestureZoom ->
                                zoom = (zoom * gestureZoom).coerceIn(1f, 5f)
                                val newMaxPanX = size.width * (zoom - 1) / 2f
                                panX = if (zoom <= 1f) 0f else (panX + pan.x).coerceIn(-newMaxPanX, newMaxPanX)
                            }
                        }
                        .pointerInput(uiState.mode) {
                            // A single finger pans horizontally once zoomed in, same gesture a
                            // vertical scroll would use — deliberately never consumed so
                            // LazyColumn's own scroll keeps working at the same time (see
                            // detectPinchZoom's doc comment for why consuming breaks that).
                            if (uiState.mode != ReaderMode.VIEW) return@pointerInput
                            detectSingleFingerHorizontalPan(isPannable = { zoom > 1f }) { dx ->
                                val bound = size.width * (zoom - 1) / 2f
                                panX = (panX + dx).coerceIn(-bound, bound)
                            }
                        }
                        .pointerInput(uiState.mode, uiState.outline.isNotEmpty()) {
                            if (uiState.mode != ReaderMode.VIEW || uiState.outline.isEmpty()) return@pointerInput
                            detectLeftEdgeSwipe(edgeBand = EDGE_SWIPE_BAND.toPx(), threshold = EDGE_SWIPE_THRESHOLD.toPx()) {
                                showOutline = true
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
                            drawColor = Color(uiState.markerColor.argb),
                            drawStrokeWidth = uiState.markerThickness.px,
                            renderPage = viewModel::renderPage,
                            pageAspectRatio = viewModel::pageAspectRatio,
                            onHighlightCreated = { rect -> viewModel.addHighlight(pageIndex, rect, highlightColor) },
                            onNoteRequested = { anchor -> noteDialogAnchor = PendingNote(pageIndex, anchor) },
                            onDrawingCreated = { points -> viewModel.addDrawing(pageIndex, points) },
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
            downloadingReferenceIndex = uiState.downloadingReferenceIndex,
            onReferenceClick = { ref ->
                showReferences = false
                viewModel.openReference(ref, onOpened = onOpenInBrowser)
            },
            onDownloadClick = { ref ->
                viewModel.downloadReferenceToLibrary(ref) {
                    showReferences = false
                    onOpenInBrowser()
                }
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
        val references = viewModel.referencesForCitation(citation)
        CitationDialog(
            citation = citation,
            references = references,
            onOpen = { reference ->
                inspectedCitation = null
                viewModel.openReference(reference, onOpened = onOpenInBrowser)
            },
            onDismiss = { inspectedCitation = null },
        )
    }
}

@Composable
private fun AnnotationModeBar(
    mode: ReaderMode,
    canUndo: Boolean,
    onModeSelected: (ReaderMode) -> Unit,
    onUndo: () -> Unit,
) {
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
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(Icons.Filled.Undo, contentDescription = "Undo last annotation")
        }
    }
}

@Composable
private fun MarkerPickerBar(
    color: MarkerColor,
    thickness: MarkerThickness,
    onColorSelected: (MarkerColor) -> Unit,
    onThicknessSelected: (MarkerThickness) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MarkerColor.entries.forEach { candidate ->
                Box(
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(28.dp)
                        .background(Color(candidate.argb), shape = androidx.compose.foundation.shape.CircleShape)
                        .then(
                            if (candidate == color) {
                                Modifier.border(3.dp, MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape)
                            } else Modifier,
                        )
                        .clickable { onColorSelected(candidate) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            MarkerThickness.entries.forEach { candidate ->
                val selected = candidate == thickness
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(36.dp)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .clickable { onThicknessSelected(candidate) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 20.dp, height = (candidate.px / 2).dp)
                            .background(MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(50)),
                    )
                }
            }
        }
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
    drawColor: Color,
    drawStrokeWidth: Float,
    renderPage: suspend (Int, Int) -> Bitmap?,
    pageAspectRatio: suspend (Int) -> Float,
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
                drawColor = drawColor,
                drawStrokeWidth = drawStrokeWidth,
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
private fun CitationDialog(
    citation: InlineCitation,
    references: List<ParsedReference>,
    onOpen: (ParsedReference) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reference ${citation.referenceIndices.joinToString(", ")}") },
        text = {
            when {
                references.isEmpty() -> Text("Couldn't find this reference in the bibliography.")
                references.size == 1 -> Text(references.first().text)
                // A grouped marker like "[3, 7]" or "[4-6]" — list every entry so the user can
                // pick which one they actually meant, instead of silently opening only the first.
                else -> LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(references, key = { it.index }) { ref ->
                        Text(
                            "${ref.index}. ${ref.text}",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(ref) }
                                .padding(vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (references.size == 1) {
                TextButton(onClick = { onOpen(references.first()) }) { Text("Open") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReferencesSheet(
    references: List<ParsedReference>,
    loading: Boolean,
    downloadingReferenceIndex: Int?,
    onReferenceClick: (ParsedReference) -> Unit,
    onDownloadClick: (ParsedReference) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("References", style = MaterialTheme.typography.titleLarge)
            Text(
                "Tap a reference to open it in a new browser tab, or tap the download icon to " +
                    "save an open-access PDF (e.g. arXiv) straight to your library.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                references.isEmpty() -> Text("No reference list detected in this PDF.")
                else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    items(references, key = { it.index }) { ref ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${ref.index}. ${ref.text}",
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onReferenceClick(ref) }
                                    .padding(vertical = 8.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (downloadingReferenceIndex == ref.index) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(8.dp))
                            } else {
                                IconButton(onClick = { onDownloadClick(ref) }) {
                                    Icon(Icons.Filled.Download, contentDescription = "Download to library")
                                }
                            }
                        }
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

/**
 * Single-finger horizontal panning, active only while [isPannable] (zoomed in). Deliberately
 * never calls [androidx.compose.ui.input.pointer.PointerInputChange.consume] — same reasoning
 * as [detectPinchZoom] above: LazyColumn's own scrollable modifier reads the very same raw
 * pointer stream for vertical scrolling, and only stops working if something else consumes it
 * first. Left un-consumed, both run off the same drag simultaneously, giving free diagonal pan.
 */
private suspend fun PointerInputScope.detectSingleFingerHorizontalPan(isPannable: () -> Boolean, onPanX: (Float) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            if (event.changes.size == 1 && isPannable()) {
                val change = event.changes[0]
                val dx = change.positionChange().x
                if (dx != 0f) onPanX(dx)
            }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * Recognizes a left-to-right drag starting within [edgeBand] of the left screen edge (like a
 * navigation-drawer swipe) and fires [onOpen] once the horizontal distance clears [threshold]
 * and clearly dominates over vertical movement. Touches that start outside the edge band are
 * ignored immediately so ordinary vertical scrolling anywhere else on the page is untouched.
 */
private suspend fun PointerInputScope.detectLeftEdgeSwipe(edgeBand: Float, threshold: Float, onOpen: () -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.position.x > edgeBand) return@awaitEachGesture
        var totalDx = 0f
        var totalDy = 0f
        do {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val delta = change.positionChange()
            totalDx += delta.x
            totalDy += delta.y
            if (totalDx > threshold && totalDx > kotlin.math.abs(totalDy) * 1.5f) {
                onOpen()
                change.consume()
                break
            }
            if (kotlin.math.abs(totalDy) > threshold) break
        } while (change.pressed)
    }
}
