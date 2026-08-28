package com.papersreader.app.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.papersreader.app.data.db.AnnotationType
import com.papersreader.app.data.pdf.InlineCitation
import com.papersreader.app.data.pdf.PdfTextSelector
import com.papersreader.app.data.pdf.PdfWord
import com.papersreader.app.data.repository.Annotation
import com.papersreader.app.data.repository.NormalizedRect
import kotlin.math.roundToInt

private const val NOTE_MARKER_RADIUS = 14f
private const val DRAW_STROKE_WIDTH = 6f
private const val HANDLE_TOUCH_SIZE_DP = 32
private const val HANDLE_DOT_SIZE_DP = 14

/**
 * Draws existing annotations, inline citation tap targets and search-match highlights over a
 * rendered page, and turns raw touch input into new annotations depending on [mode]:
 *  - HIGHLIGHT: drag to draw a rectangle, released -> [onHighlightCreated].
 *  - NOTE: tap a spot -> [onNoteRequested] with that point.
 *  - DRAW: freehand finger drawing, released -> [onDrawingCreated].
 *  - VIEW: tap a citation marker -> [onCitationTapped]; tap an existing annotation ->
 *    [onAnnotationTapped]; long-press a word and drag -> select text, the same as long-pressing
 *    text anywhere else on the phone, with draggable handles and a "Copy" button once released
 *    (-> [onCopyRequested]); tapping empty space clears an active selection.
 *
 * A genuinely native `SelectionContainer`-based version was tried and reverted: Compose's own
 * selection implementation claims the *entire* area it covers for pointer-input arbitration (not
 * just long-presses), which silently ate every citation tap underneath it, and its handle/highlight
 * geometry didn't track this screen's custom pinch-zoom `graphicsLayer` correctly. This hand-rolled
 * version coexists with both because it's built from the same low-level, deliberately
 * non-consuming gesture primitives already used elsewhere in this file/screen.
 */
@Composable
fun AnnotationOverlay(
    modifier: Modifier = Modifier,
    mode: ReaderMode,
    // The selection handles/Copy button are drawn *inside* the same subtree the caller applies
    // its pinch-zoom graphicsLayer scale to (see ReaderScreen's own comments on why zoom lives
    // there, at the LazyColumn level) — every dp size in this file is otherwise defined once and
    // implicitly scales visually along with the zoomed page, which is correct for content that's
    // part of the page (highlights, citation boxes) but wrong for UI chrome that should stay a
    // constant on-screen size regardless of zoom, the same way the OS's own selection handles
    // never grow just because the page under them is zoomed in. [zoom] lets those specific pieces
    // divide their target size by the current zoom before the ancestor multiplies it back out.
    zoom: Float = 1f,
    annotations: List<Annotation>,
    pageSizePx: IntSize,
    citations: List<InlineCitation> = emptyList(),
    words: List<PdfWord> = emptyList(),
    searchHighlights: List<NormalizedRect> = emptyList(),
    activeSearchHighlight: NormalizedRect? = null,
    drawColor: Color = Color(0xFFE53935),
    drawStrokeWidth: Float = DRAW_STROKE_WIDTH,
    onHighlightCreated: (NormalizedRect) -> Unit,
    onNoteRequested: (NormalizedRect) -> Unit,
    onDrawingCreated: (List<NormalizedRect>) -> Unit = {},
    onAnnotationTapped: (Annotation) -> Unit,
    onCitationTapped: (InlineCitation) -> Unit = {},
    onCopyRequested: (List<PdfWord>) -> Unit = {},
) {
    var dragStart by remember(mode) { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember(mode) { mutableStateOf<Offset?>(null) }
    var drawPoints by remember(mode) { mutableStateOf<List<Offset>>(emptyList()) }
    // Text selection state lives here rather than being lifted up like other annotation types —
    // it's transient, per-page UI state (handles, a floating Copy button) rather than data that
    // needs to survive recomposition of the reader screen, so there's nothing for a caller to do
    // with it except get told when a copy actually happens.
    var selection by remember(mode) { mutableStateOf<List<PdfWord>>(emptyList()) }
    // True only while a long-press-drag is actively sweeping out a *new* selection — the handles
    // and Copy button only make sense once the finger lifts and the range is settled, the same
    // way the system text-selection toolbar waits for the drag to end before it appears.
    var isSelecting by remember(mode) { mutableStateOf(false) }
    // Citation markers are often just a few characters ("[FB81]") — a tap landing a couple of
    // pixels outside the glyphs' own tight bounding box (normal finger imprecision) otherwise
    // missed every time. Inflating just the *hit-test* rect (not anything drawn) by a small,
    // density-independent margin makes tapping reliable without changing how anything looks.
    // The downward margin is deliberately much larger: users reported reliably needing to tap
    // *below* a marker to hit it, i.e. real finger taps land low relative to the glyph box more
    // often than they land high (a citation sits right at the end of a text line, and a thumb
    // covering it tends to register lower than where the eye aims) — so the slop is asymmetric
    // rather than growing the already-fine top/side margins to match.
    val citationHitSlopPx = with(LocalDensity.current) { 6.dp.toPx() }
    val citationHitSlopBottomPx = with(LocalDensity.current) { 22.dp.toPx() }
    // Handles/the Copy button live in the same zoomed subtree as the page content, so their own
    // sizes need to shrink by this factor for the ancestor's zoom to bring them back to a
    // constant on-screen size — see the [zoom] parameter's own doc comment for why.
    val inverseZoom = 1f / zoom.coerceAtLeast(0.05f)

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(mode, pageSizePx, citations, annotations) {
                    when (mode) {
                        ReaderMode.HIGHLIGHT -> detectDragGestures(
                            onDragStart = { offset ->
                                dragStart = offset
                                dragCurrent = offset
                            },
                            onDrag = { change, _ -> dragCurrent = change.position },
                            onDragEnd = {
                                val start = dragStart
                                val end = dragCurrent
                                if (start != null && end != null && pageSizePx.width > 0 && pageSizePx.height > 0) {
                                    onHighlightCreated(normalizedRect(start, end, pageSizePx))
                                }
                                dragStart = null
                                dragCurrent = null
                            },
                            onDragCancel = {
                                dragStart = null
                                dragCurrent = null
                            },
                        )
                        ReaderMode.NOTE -> detectTapGestures { offset ->
                            if (pageSizePx.width > 0 && pageSizePx.height > 0) {
                                onNoteRequested(pointToNormalizedAnchor(offset, pageSizePx))
                            }
                        }
                        ReaderMode.DRAW -> detectDragGestures(
                            onDragStart = { offset -> drawPoints = listOf(offset) },
                            onDrag = { change, _ -> drawPoints = drawPoints + change.position },
                            onDragEnd = {
                                if (drawPoints.size >= 2 && pageSizePx.width > 0 && pageSizePx.height > 0) {
                                    onDrawingCreated(drawPoints.map { pointToNormalizedAnchor(it, pageSizePx) })
                                }
                                drawPoints = emptyList()
                            },
                            onDragCancel = { drawPoints = emptyList() },
                        )
                        ReaderMode.VIEW -> detectTapGestures { offset ->
                            val citationHit = citations.lastOrNull { citation ->
                                citation.rects.any {
                                    inflatedRect(denormalize(it, pageSizePx), citationHitSlopPx, citationHitSlopBottomPx).contains(offset)
                                }
                            }
                            if (citationHit != null) {
                                onCitationTapped(citationHit)
                                return@detectTapGestures
                            }
                            val annotationHit = annotations.lastOrNull { annotation ->
                                annotation.rects.any { rect -> denormalize(rect, pageSizePx).contains(offset) }
                            }
                            if (annotationHit != null) {
                                onAnnotationTapped(annotationHit)
                                return@detectTapGestures
                            }
                            // A tap that doesn't land on anything else dismisses an active
                            // selection, same as tapping empty space anywhere else on the phone.
                            if (selection.isNotEmpty()) selection = emptyList()
                        }
                    }
                }
                // Runs concurrently with the tap detector above rather than as another branch of
                // it: Compose's tap and long-press-drag detectors are designed to coexist on the
                // same node, each independently watching the same raw pointer stream — once this
                // one confirms a long press and starts consuming position changes, the plain tap
                // detector above sees that consumption and quietly cancels its own onTap instead
                // of firing, so a long-press-drag never *also* registers as a stray tap.
                .pointerInput(mode, pageSizePx, words) {
                    if (mode != ReaderMode.VIEW) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            isSelecting = true
                            dragStart = offset
                            selection = wordsAt(words, offset, offset, pageSizePx)
                        },
                        onDrag = { change, _ ->
                            val start = dragStart ?: return@detectDragGesturesAfterLongPress
                            selection = wordsAt(words, start, change.position, pageSizePx)
                        },
                        onDragEnd = {
                            isSelecting = false
                            dragStart = null
                        },
                        onDragCancel = {
                            isSelecting = false
                            dragStart = null
                        },
                    )
                },
        ) {
            searchHighlights.forEach { rect ->
                val r = denormalize(rect, pageSizePx)
                val isActive = rect == activeSearchHighlight
                drawRect(
                    color = (if (isActive) Color(0xFFFF9800) else Color(0xFFFFEB3B)).copy(alpha = if (isActive) 0.6f else 0.4f),
                    topLeft = r.topLeft,
                    size = r.size,
                )
            }
            selectionLineRects(selection).forEach { rect ->
                val r = denormalize(rect, pageSizePx)
                drawRect(color = Color(0xFF2979FF).copy(alpha = 0.35f), topLeft = r.topLeft, size = r.size)
            }
            annotations.forEach { annotation ->
                drawAnnotation(annotation, pageSizePx)
            }
            val start = dragStart
            val current = dragCurrent
            if (mode == ReaderMode.HIGHLIGHT && start != null && current != null) {
                drawDraftRect(start, current)
            }
            if (mode == ReaderMode.DRAW && drawPoints.size >= 2) {
                drawPath(pointsToPath(drawPoints), color = drawColor, style = Stroke(width = drawStrokeWidth))
            }
        }

        if (mode == ReaderMode.VIEW && selection.isNotEmpty() && !isSelecting) {
            val lineRectsPx = selectionLineRects(selection).map { denormalize(it, pageSizePx) }
            val firstRect = lineRectsPx.first()
            val lastRect = lineRectsPx.last()

            SelectionHandle(
                xPx = firstRect.left,
                yPx = firstRect.bottom,
                inverseZoom = inverseZoom,
                onDrag = { newOffset ->
                    val fixedEnd = denormalize(selection.last().rect, pageSizePx)
                    selection = wordsAt(words, newOffset, Offset(fixedEnd.right, fixedEnd.bottom), pageSizePx)
                },
            )
            SelectionHandle(
                xPx = lastRect.right,
                yPx = lastRect.bottom,
                inverseZoom = inverseZoom,
                onDrag = { newOffset ->
                    val fixedStart = denormalize(selection.first().rect, pageSizePx)
                    selection = wordsAt(words, Offset(fixedStart.left, fixedStart.bottom), newOffset, pageSizePx)
                },
            )

            val bounds = boundingBox(lineRectsPx)
            val buttonHalfWidthPx = COPY_BUTTON_HALF_WIDTH_PX * inverseZoom
            val buttonHeightPx = COPY_BUTTON_HEIGHT_PX * inverseZoom
            val buttonMarginPx = COPY_BUTTON_MARGIN_PX * inverseZoom
            Surface(
                modifier = Modifier.offset {
                    IntOffset(
                        (bounds.left + bounds.width / 2 - buttonHalfWidthPx).roundToInt(),
                        (bounds.top - buttonHeightPx - buttonMarginPx).roundToInt(),
                    )
                },
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp * inverseZoom),
                shadowElevation = 4.dp * inverseZoom,
            ) {
                Box(
                    modifier = Modifier
                        .pointerInput(selection) {
                            detectTapGestures {
                                onCopyRequested(selection)
                                selection = emptyList()
                            }
                        }
                        .padding(horizontal = 14.dp * inverseZoom, vertical = 8.dp * inverseZoom),
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Copy",
                        tint = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.size(24.dp * inverseZoom),
                    )
                }
            }
        }
    }
}

/** A small draggable circle at one end of a text selection, matching the system's own text
 *  handles — dragging it re-anchors that end of the selection while the other end stays fixed.
 *  [inverseZoom] keeps its on-screen size constant regardless of how far the page is zoomed in. */
@Composable
private fun SelectionHandle(xPx: Float, yPx: Float, inverseZoom: Float, onDrag: (Offset) -> Unit) {
    val liveX by rememberUpdatedState(xPx)
    val liveY by rememberUpdatedState(yPx)
    val touchSizeDp = HANDLE_TOUCH_SIZE_DP.dp * inverseZoom
    val dotSizeDp = HANDLE_DOT_SIZE_DP.dp * inverseZoom
    val touchRadiusPx = with(LocalDensity.current) { (touchSizeDp / 2).toPx() }
    val liveTouchRadiusPx by rememberUpdatedState(touchRadiusPx)
    Box(
        modifier = Modifier
            .offset { IntOffset((xPx - touchRadiusPx).roundToInt(), yPx.roundToInt()) }
            .size(touchSizeDp)
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    // The box's own origin already moves to follow [liveX]/[liveY] each time the
                    // selection (and therefore this handle's position) changes, so the touch
                    // point's *local* offset within it, read fresh on every event, is already in
                    // the same page-pixel space as everything else here — no delta accumulation
                    // needed.
                    onDrag(Offset(liveX - liveTouchRadiusPx + change.position.x, liveY + change.position.y))
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .padding(top = (touchSizeDp - dotSizeDp) / 2)
                .size(dotSizeDp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
    }
}

private fun DrawScope.drawAnnotation(annotation: Annotation, pageSizePx: IntSize) {
    val color = Color(annotation.color)
    when (annotation.type) {
        AnnotationType.HIGHLIGHT -> annotation.rects.forEach { rect ->
            val r = denormalize(rect, pageSizePx)
            drawRect(color = color.copy(alpha = 0.35f), topLeft = r.topLeft, size = r.size)
        }
        AnnotationType.NOTE -> annotation.rects.firstOrNull()?.let { rect ->
            val r = denormalize(rect, pageSizePx)
            drawCircle(color = color, radius = NOTE_MARKER_RADIUS, center = r.topLeft)
        }
        AnnotationType.DRAWING -> if (annotation.rects.size >= 2) {
            val points = annotation.rects.map { denormalize(it, pageSizePx).topLeft }
            drawPath(pointsToPath(points), color = color, style = Stroke(width = annotation.strokeWidth ?: DRAW_STROKE_WIDTH))
        }
    }
}

private fun pointsToPath(points: List<Offset>): Path = Path().apply {
    points.firstOrNull()?.let { moveTo(it.x, it.y) }
    points.drop(1).forEach { lineTo(it.x, it.y) }
}

private fun DrawScope.drawDraftRect(start: Offset, current: Offset) {
    val topLeft = Offset(minOf(start.x, current.x), minOf(start.y, current.y))
    val size = androidx.compose.ui.geometry.Size(
        kotlin.math.abs(current.x - start.x),
        kotlin.math.abs(current.y - start.y),
    )
    drawRect(color = Color(0xFFFFEB3B).copy(alpha = 0.45f), topLeft = topLeft, size = size)
}

private fun normalizedRect(a: Offset, b: Offset, pageSizePx: IntSize): NormalizedRect {
    val left = minOf(a.x, b.x) / pageSizePx.width
    val top = minOf(a.y, b.y) / pageSizePx.height
    val right = maxOf(a.x, b.x) / pageSizePx.width
    val bottom = maxOf(a.y, b.y) / pageSizePx.height
    return NormalizedRect(left, top, right, bottom)
}

private fun pointToNormalizedAnchor(point: Offset, pageSizePx: IntSize): NormalizedRect {
    val x = point.x / pageSizePx.width
    val y = point.y / pageSizePx.height
    return NormalizedRect(x, y, x, y)
}

private fun inflatedRect(rect: Rect, delta: Float, bottomDelta: Float = delta): Rect = Rect(
    left = rect.left - delta,
    top = rect.top - delta,
    right = rect.right + delta,
    bottom = rect.bottom + bottomDelta,
)

/** Normalizes both drag endpoints and resolves them to a word range via [PdfTextSelector]. */
private fun wordsAt(words: List<PdfWord>, from: Offset, to: Offset, pageSizePx: IntSize): List<PdfWord> {
    if (pageSizePx.width <= 0 || pageSizePx.height <= 0) return emptyList()
    return PdfTextSelector.wordsInRange(
        words,
        fromX = from.x / pageSizePx.width,
        fromY = from.y / pageSizePx.height,
        toX = to.x / pageSizePx.width,
        toY = to.y / pageSizePx.height,
    )
}

/** Groups consecutive selected words (already in reading order) into one rect per printed line. */
private fun selectionLineRects(selection: List<PdfWord>): List<NormalizedRect> {
    if (selection.isEmpty()) return emptyList()
    val rects = mutableListOf<NormalizedRect>()
    var current = selection.first().rect
    for (word in selection.drop(1)) {
        current = if (current.top < word.rect.bottom && word.rect.top < current.bottom) {
            NormalizedRect(
                left = minOf(current.left, word.rect.left),
                top = minOf(current.top, word.rect.top),
                right = maxOf(current.right, word.rect.right),
                bottom = maxOf(current.bottom, word.rect.bottom),
            )
        } else {
            rects += current
            word.rect
        }
    }
    rects += current
    return rects
}

private fun boundingBox(rects: List<Rect>): Rect = rects.reduce { a, b ->
    Rect(
        left = minOf(a.left, b.left),
        top = minOf(a.top, b.top),
        right = maxOf(a.right, b.right),
        bottom = maxOf(a.bottom, b.bottom),
    )
}

private fun denormalize(rect: NormalizedRect, pageSizePx: IntSize): Rect = Rect(
    left = rect.left * pageSizePx.width,
    top = rect.top * pageSizePx.height,
    right = rect.right * pageSizePx.width,
    bottom = rect.bottom * pageSizePx.height,
)

private const val COPY_BUTTON_HALF_WIDTH_PX = 42f
private const val COPY_BUTTON_HEIGHT_PX = 40f
private const val COPY_BUTTON_MARGIN_PX = 12f
