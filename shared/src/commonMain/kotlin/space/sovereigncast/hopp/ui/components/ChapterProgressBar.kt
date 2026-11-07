package space.sovereigncast.hopp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The progress bar of the now-playing screen: chapter boundaries are gaps in
 * the track (ADR 0019). Tap or drag to seek; while dragging the knob follows
 * the finger and the seek happens on release. Meanwhile [preview] names the
 * place in a bubble over the knob, and [onPreview] tells the screen, whose
 * times below follow too (device feedback 2026-10-21: as YouTube does). A mouse
 * resting over the bar gets the same bubble, and nothing else moves (maintainer
 * 2026-11-07: which chapter is this, before jumping there).
 */
@Composable
fun ChapterProgressBar(
    fraction: Float,
    boundaries: List<Float>,
    onSeek: (Float) -> Unit,
    trackColor: Color,
    fillColor: Color,
    gapColor: Color,
    label: String,
    modifier: Modifier = Modifier,
    preview: ((Float) -> String)? = null,
    onPreview: (Float?) -> Unit = {},
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var hoverFraction by remember { mutableStateOf<Float?>(null) }
    val shown = (dragFraction ?: fraction).coerceIn(0f, 1f)
    val drag = { f: Float? -> dragFraction = f; onPreview(f) }
    Box(modifier.fillMaxWidth()) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull() ?: continue
                        hoverFraction = if (pointer.type == PointerType.Mouse && event.type != PointerEventType.Exit) (pointer.position.x / size.width).coerceIn(0f, 1f) else null
                    }
                }
            }
            .pointerInput(Unit) { detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { drag((it.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = { dragFraction?.let(onSeek); drag(null) },
                    onDragCancel = { drag(null) },
                ) { change, _ -> drag((change.position.x / size.width).coerceIn(0f, 1f)) }
            },
    ) {
        val h = 6.dp.toPx()
        val y = size.height / 2 - h / 2
        val r = CornerRadius(h / 2, h / 2)
        drawRoundRect(trackColor, Offset(0f, y), Size(size.width, h), r)
        drawRoundRect(fillColor, Offset(0f, y), Size(size.width * shown, h), r)
        val gap = 3.dp.toPx()
        for (b in boundaries) drawRect(gapColor, Offset(size.width * b - gap / 2, y), Size(gap, h))
        drawCircle(fillColor, radius = 7.5.dp.toPx(), center = Offset(size.width * shown, size.height / 2))
    }
    val f = dragFraction ?: hoverFraction
    if (f != null && preview != null) Text(
        preview(f),
        Modifier
            // Over the knob, kept inside the bar's width; it takes no room of its own.
            .layout { measurable, constraints ->
                val bubble = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val x = (constraints.maxWidth * f - bubble.width / 2f).toInt().coerceIn(0, maxOf(0, constraints.maxWidth - bubble.width))
                layout(0, 0) { bubble.place(x, -bubble.height - 2.dp.roundToPx()) }
            }
            .background(fillColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = gapColor, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
    }
}
