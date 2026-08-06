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
import androidx.compose.foundation.gestures.calculateCentroid
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.TransformOrigin
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val highlightColor = androidx.compose.ui.graphics.Color(0xFFFFEB3B).toArgb()
private val noteColor = androidx.compose.ui.graphics.Color(0xFF2196F3).toArgb()

/** Render pages a bit sharper than the screen so pinch-zoom stays reasonably crisp. */
private const val RENDER_SCALE_FACTOR = 2

private data class PendingNote(val page: Int, val anchor: NormalizedRect)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    paperId: Long,
    onBack: () -> Unit,
    onOpenPaper: (Long) -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    LaunchedEffect(paperId) { viewModel.open(paperId) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val openInSystemBrowser: (String) -> Unit = { url ->
        runCatching {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }
    }

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

    // Built with the paper's last-read page baked in as its *initial* index (re-keyed once
    // pageCount actually arrives, so the first real composition of this list already starts
    // there) rather than created empty and scrolled to position afterwards — that gives the
    // retry loop below a head start, though it alone isn't enough to land exactly on the target;
    // see that loop's comment for why.
    val listState = remember(uiState.pageCount > 0) {
        val target = if (uiState.pageCount > 0) uiState.currentPage.coerceIn(0, uiState.pageCount - 1) else 0
        LazyListState(firstVisibleItemIndex = target)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var zoom by remember { mutableStateOf(1f) }
    var panX by remember { mutableStateOf(0f) }
    var panY by remember { mutableStateOf(0f) }

    LaunchedEffect(uiState.libraryMessage) {
        uiState.libraryMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.dismissLibraryMessage()
        }
    }

    // A reference download that succeeds should open, not just silently save — mirrors tapping
    // it in the library right after import.
    LaunchedEffect(uiState.openPaperId) {
        uiState.openPaperId?.let { newPaperId ->
            showReferences = false
            inspectedCitation = null
            viewModel.consumeOpenPaperId()
            onOpenPaper(newPaperId)
        }
    }

    // Track which page is mostly at the top of the viewport as the "current" page.
    LaunchedEffect(listState) {
        // Each page's height only settles once its own aspect ratio has loaded (a separate
        // suspend call per page, resolved as that item gets composed) — a lazily-composed page
        // this list hasn't reached yet still reports a tiny placeholder height (just its loading
        // spinner) until then. A single scrollToItem() called right after this list first
        // appears can therefore only jump as far as whatever placeholder-sized heights are known
        // *at that instant*, landing well short of the real target. Retrying gives each
        // still-loading skipped page a chance to report its real height before the next attempt,
        // converging on the true target instead of silently settling wherever that first,
        // premature jump happened to land.
        val target = uiState.currentPage.coerceIn(0, maxOf(uiState.pageCount - 1, 0))
        var attempts = 0
        while (listState.firstVisibleItemIndex < target && attempts < 40) {
            listState.scrollToItem(target)
            attempts++
            if (listState.firstVisibleItemIndex < target) delay(100)
        }
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { page -> if (page != uiState.currentPage) viewModel.onPageChanged(page, zoom) }
    }

    // Restore the zoom the paper was last closed at, once the document has actually loaded.
    // (The initial *page* is instead baked into `listState`'s construction above — see its
    // comment for why a post-hoc scrollToItem() here didn't work.) Guarded so it only runs once
    // per paper, not on every later pageCount change.
    var restoredInitialZoom by remember(paperId) { mutableStateOf(false) }
    LaunchedEffect(uiState.pageCount) {
        if (uiState.pageCount > 0 && !restoredInitialZoom) {
            restoredInitialZoom = true
            zoom = uiState.initialZoom.coerceIn(1f, 5f)
        }
    }

    // Zoom can change without the visible page ever changing (e.g. pinch-zoom then leave) —
    // capture the final state on the way out so it isn't lost. Keyed on `listState` itself
    // (not just paperId): `listState` is *recreated* once pageCount arrives (see its own
    // comment above), and a DisposableEffect keyed only on the never-changing paperId would
    // keep referencing whichever `listState` object was in scope the very first time it ran —
    // the short-lived placeholder from before the document loaded, permanently stuck at index
    // 0 — instead of the real one the LazyColumn actually scrolls.
    DisposableEffect(listState) {
        onDispose { viewModel.saveReadingState(listState.firstVisibleItemIndex, zoom) }
    }

    LaunchedEffect(uiState.jumpToPage) {
        uiState.jumpToPage?.let { page ->
            zoom = 1f
            panX = 0f
            panY = 0f
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
                // The list only handles its own scroll at 1x; once zoomed in, panning (both
                // pinch-drag and single-finger) takes over so it can move freely in both axes
                // like pinch-zooming a webpage, instead of fighting the list's vertical-only
                // native scroll.
                val zoomed = zoom > 1f
                LazyColumn(
                    state = listState,
                    userScrollEnabled = uiState.mode == ReaderMode.VIEW && !zoomed,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(uiState.mode) {
                            // Pivots the scale change around the pinch's actual focal point
                            // instead of a fixed origin — with the layer's transformOrigin set to
                            // TopStart below, panX/panY *are* the translation applied post-scale,
                            // so keeping the point under the fingers visually fixed as zoom
                            // changes (and folding in the raw two-finger drag on top) is a single
                            // update: newPan = focal*(1-ratio) + pan + ratio*oldPan. See
                            // detectPinchZoom's doc comment for the derivation.
                            if (uiState.mode != ReaderMode.VIEW) return@pointerInput
                            detectPinchZoom { centroid, pan, gestureZoom ->
                                val oldZoom = zoom
                                val newZoom = (oldZoom * gestureZoom).coerceIn(1f, 5f)
                                val ratio = if (oldZoom != 0f) newZoom / oldZoom else 1f
                                zoom = newZoom

                                val minX = size.width * (1 - newZoom)
                                panX = (centroid.x * (1 - ratio) + pan.x + ratio * panX).coerceIn(minX, 0f)

                                val minY = size.height * (1 - newZoom)
                                val rawY = centroid.y * (1 - ratio) + pan.y + ratio * panY
                                val clampedY = rawY.coerceIn(minY, 0f)
                                val leftoverY = rawY - clampedY
                                panY = clampedY
                                if (leftoverY != 0f) listState.dispatchRawDelta(-leftoverY / newZoom)
                            }
                        }
                        .pointerInput(uiState.mode) {
                            // Free 2D pan once zoomed in, deliberately never consumed so the
                            // pinch detector above keeps working at the same time (see its doc
                            // comment for why consuming single-finger events breaks that).
                            // Horizontal panning simply clamps at the page edge — there's
                            // nowhere else for it to go — but vertical panning hands any
                            // overflow past its local budget off to the list's own scroll via
                            // dispatchRawDelta, so dragging past what a single zoomed viewport
                            // can show keeps scrolling into the next page instead of just
                            // stopping ("hitting a wall") while still visually mid-document.
                            if (uiState.mode != ReaderMode.VIEW) return@pointerInput
                            detectSingleFingerFreePan(isPannable = { zoom > 1f }) { dx, dy ->
                                val minX = size.width * (1 - zoom)
                                panX = (panX + dx).coerceIn(minX, 0f)

                                val minY = size.height * (1 - zoom)
                                val target = panY + dy
                                val clamped = target.coerceIn(minY, 0f)
                                val leftover = target - clamped
                                panY = clamped
                                if (leftover != 0f) listState.dispatchRawDelta(-leftover / zoom)
                            }
                        }
                        .graphicsLayer(
                            scaleX = zoom,
                            scaleY = zoom,
                            translationX = panX,
                            translationY = panY,
                            transformOrigin = TransformOrigin(0f, 0f),
                        ),
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
                viewModel.openReference(ref, onResolved = openInSystemBrowser)
            },
            onDownloadClick = { ref -> viewModel.downloadReferenceToLibrary(ref) },
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
            downloadingReferenceIndex = uiState.downloadingReferenceIndex,
            onOpen = { reference ->
                inspectedCitation = null
                viewModel.openReference(reference, onResolved = openInSystemBrowser)
            },
            onDownload = { reference -> viewModel.downloadReferenceToLibrary(reference) },
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
    downloadingReferenceIndex: Int?,
    onOpen: (ParsedReference) -> Unit,
    onDownload: (ParsedReference) -> Unit,
    onDismiss: () -> Unit,
) {
    // Deliberately the exact same row shape regardless of how many references this marker
    // grouped together (even just one) — tap the text to open it, tap the icon to download it.
    // A single-reference marker used to get its own special pair of Open/Download buttons
    // instead, which meant the interaction differed depending on the marker you happened to tap.
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reference ${citation.label}") },
        text = {
            if (references.isEmpty()) {
                Text("Couldn't find this reference in the bibliography.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(references, key = { it.index }) { ref ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "${ref.index}. ${ref.text}",
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onOpen(ref) }
                                    .padding(vertical = 8.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (downloadingReferenceIndex == ref.index) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(8.dp))
                            } else {
                                IconButton(onClick = { onDownload(ref) }) {
                                    Icon(Icons.Filled.Download, contentDescription = "Download to library")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
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
                "Tap a reference to open it in your browser, or tap the download icon to save an " +
                    "open-access PDF straight to your library and read it right away.",
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
 *
 * Reports the gesture's focal point (centroid of the touches, in the same untransformed
 * coordinate space pointer input always sees regardless of the graphicsLayer scale/translation
 * applied further down the modifier chain) alongside the usual pan/zoom deltas, so the caller can
 * scale *around that point* — without it, the only available pivot is graphicsLayer's fixed
 * transformOrigin, which makes the content appear to zoom from a corner instead of from between
 * the fingers.
 */
private suspend fun PointerInputScope.detectPinchZoom(onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            if (event.changes.size >= 2) {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                val centroid = event.calculateCentroid(useCurrent = true)
                if (zoomChange != 1f || panChange != Offset.Zero) {
                    onGesture(centroid, panChange, zoomChange)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * Single-finger 2D panning, active only while [isPannable] (zoomed in). Deliberately never
 * calls [androidx.compose.ui.input.pointer.PointerInputChange.consume] — same reasoning as
 * [detectPinchZoom] above: LazyColumn's own scrollable modifier reads the very same raw pointer
 * stream for vertical scrolling, and only stops working if something else consumes it first.
 * Left un-consumed, both run off the same drag simultaneously, giving free diagonal pan.
 */
private suspend fun PointerInputScope.detectSingleFingerFreePan(isPannable: () -> Boolean, onPan: (dx: Float, dy: Float) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            if (event.changes.size == 1 && isPannable()) {
                val change = event.changes[0]
                val delta = change.positionChange()
                if (delta.x != 0f || delta.y != 0f) onPan(delta.x, delta.y)
            }
        } while (event.changes.any { it.pressed })
    }
}
