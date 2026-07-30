package com.papersreader.app.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import com.papersreader.app.data.db.AnnotationType
import com.papersreader.app.data.repository.Annotation
import com.papersreader.app.data.repository.NormalizedRect

private const val NOTE_MARKER_RADIUS = 14f

/**
 * Draws existing highlights/note markers over a rendered page and turns raw touch input into
 * new annotations depending on [mode]:
 *  - HIGHLIGHT: drag to draw a rectangle, released -> [onHighlightCreated].
 *  - NOTE: tap a spot -> [onNoteRequested] with that point.
 *  - VIEW: tap an existing annotation -> [onAnnotationTapped].
 */
@Composable
fun AnnotationOverlay(
    modifier: Modifier = Modifier,
    mode: ReaderMode,
    annotations: List<Annotation>,
    pageSizePx: androidx.compose.ui.unit.IntSize,
    onHighlightCreated: (NormalizedRect) -> Unit,
    onNoteRequested: (NormalizedRect) -> Unit,
    onAnnotationTapped: (Annotation) -> Unit,
) {
    var dragStart by remember(mode) { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember(mode) { mutableStateOf<Offset?>(null) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(mode, pageSizePx) {
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
                    ReaderMode.VIEW -> detectTapGestures { offset ->
                        val hit = annotations.lastOrNull { annotation ->
                            annotation.rects.any { rect -> denormalize(rect, pageSizePx).contains(offset) }
                        }
                        hit?.let(onAnnotationTapped)
                    }
                }
            },
    ) {
        annotations.forEach { annotation ->
            drawAnnotation(annotation, pageSizePx)
        }
        val start = dragStart
        val current = dragCurrent
        if (mode == ReaderMode.HIGHLIGHT && start != null && current != null) {
            drawDraftRect(start, current)
        }
    }
}

private fun DrawScope.drawAnnotation(annotation: Annotation, pageSizePx: androidx.compose.ui.unit.IntSize) {
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
    }
}

private fun DrawScope.drawDraftRect(start: Offset, current: Offset) {
    val topLeft = Offset(minOf(start.x, current.x), minOf(start.y, current.y))
    val size = androidx.compose.ui.geometry.Size(
        kotlin.math.abs(current.x - start.x),
        kotlin.math.abs(current.y - start.y),
    )
    drawRect(color = Color(0xFFFFEB3B).copy(alpha = 0.45f), topLeft = topLeft, size = size)
}

private fun normalizedRect(a: Offset, b: Offset, pageSizePx: androidx.compose.ui.unit.IntSize): NormalizedRect {
    val left = minOf(a.x, b.x) / pageSizePx.width
    val top = minOf(a.y, b.y) / pageSizePx.height
    val right = maxOf(a.x, b.x) / pageSizePx.width
    val bottom = maxOf(a.y, b.y) / pageSizePx.height
    return NormalizedRect(left, top, right, bottom)
}

private fun pointToNormalizedAnchor(point: Offset, pageSizePx: androidx.compose.ui.unit.IntSize): NormalizedRect {
    val x = point.x / pageSizePx.width
    val y = point.y / pageSizePx.height
    return NormalizedRect(x, y, x, y)
}

private fun denormalize(rect: NormalizedRect, pageSizePx: androidx.compose.ui.unit.IntSize): Rect = Rect(
    left = rect.left * pageSizePx.width,
    top = rect.top * pageSizePx.height,
    right = rect.right * pageSizePx.width,
    bottom = rect.bottom * pageSizePx.height,
)
