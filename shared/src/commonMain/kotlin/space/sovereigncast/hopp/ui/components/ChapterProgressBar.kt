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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import space.sovereigncast.hopp.ui.theme.ProgressStyle
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
 * 2026-11-07: which chapter is this, before jumping there). [fillFrom] and [heat]
 * are a design's own hand on it (PlayerLook): the heard part runs from one colour
 * to [fillColor] under a shimmer, and the knob glows; [style] shows the chapters another
 * way than by gaps, [notchColor] its notches, and [knobColor] is a knob of its own colour.
 */
@Composable
fun ChapterProgressBar(
    fraction: Float,
    boundaries: List<Float>,
    onSeek: (Float) -> Unit,
    trackColor: Color,
    fillColor: Color,
    /** What is written on [fillColor]: the bubble's text. */
    onFillColor: Color,
    label: String,
    modifier: Modifier = Modifier,
    preview: ((Float) -> String)? = null,
    onPreview: (Float?) -> Unit = {},
    fillFrom: Color? = null,
    heat: Color? = null,
    style: ProgressStyle = ProgressStyle.Gaps,
    knobColor: Color? = null,
    notchColor: Color = trackColor,
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
        val thin = style == ProgressStyle.Notches || style == ProgressStyle.Nodes
        val h = (if (thin) 2.dp else 6.dp).toPx()
        val y = size.height / 2 - h / 2
        val r = if (thin) CornerRadius.Zero else CornerRadius(h / 2, h / 2)
        val heard = size.width * shown
        val knob = Offset(heard, size.height / 2)
        val cuts = boundaries.filter { it > 0f && it < 1f }.sorted()
        val gap = when (style) { ProgressStyle.Gaps, ProgressStyle.Ring -> 3.dp; ProgressStyle.Notches -> 0.dp; ProgressStyle.Nodes -> 9.dp }.toPx()
        // Open where a chapter begins: whatever the bar lies on shows through.
        val parts = Path().apply {
            var from = 0f
            for (cut in cuts + 1f) {
                val to = if (cut < 1f) size.width * cut - gap / 2 else size.width
                if (to > from) addRect(Rect(from, 0f, to, size.height))
                from = size.width * cut + gap / 2
            }
        }
        // Open inside a hollow knob too.
        if (style == ProgressStyle.Ring) parts.op(parts, Path().apply { addOval(Rect(knob, 7.5.dp.toPx())) }, PathOperation.Difference)
        clipPath(parts) {
            drawRoundRect(trackColor, Offset(0f, y), Size(size.width, h), r)
            if (heat != null && !thin && heard > 0f) {
                val reach = 9.dp.toPx()
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 0.5f to heat.copy(alpha = 0.3f), 1f to Color.Transparent, startY = knob.y - reach, endY = knob.y + reach),
                    Offset(0f, knob.y - reach), Size(heard, reach * 2),
                )
            }
            if (fillFrom != null && heard > 0f) drawRoundRect(Brush.horizontalGradient(listOf(fillFrom, fillColor), 0f, heard), Offset(0f, y), Size(heard, h), r)
            else drawRoundRect(fillColor, Offset(0f, y), Size(heard, h), r)
        }
        when (style) {
            ProgressStyle.Gaps -> {
                if (heat != null) drawCircle(Brush.radialGradient(listOf(heat.copy(alpha = 0.8f), Color.Transparent), knob, 14.dp.toPx()), 14.dp.toPx(), knob)
                drawCircle(knobColor ?: fillColor, radius = 7.5.dp.toPx(), center = knob)
            }
            ProgressStyle.Ring -> drawCircle(knobColor ?: fillColor, 5.75.dp.toPx(), knob, style = Stroke(3.5.dp.toPx()))
            ProgressStyle.Notches -> {
                val notch = Size(1.5.dp.toPx(), 10.dp.toPx())
                for (cut in cuts) drawRect(notchColor, Offset(size.width * cut - notch.width / 2, knob.y - notch.height / 2), notch)
                if (heat != null) drawCircle(Brush.radialGradient(listOf(heat.copy(alpha = 0.9f), Color.Transparent), knob, 15.dp.toPx()), 15.dp.toPx(), knob)
                // A crystal: a square on its corner.
                val side = 12.dp.toPx()
                rotate(45f, knob) { drawRoundRect(knobColor ?: fillColor, Offset(knob.x - side / 2, knob.y - side / 2), Size(side, side), CornerRadius(2.dp.toPx())) }
            }
            ProgressStyle.Nodes -> {
                val node = 3.dp.toPx()
                for (cut in cuts) {
                    val at = Offset(size.width * cut, knob.y)
                    if (cut <= shown) {
                        if (heat != null) drawCircle(Brush.radialGradient(listOf(heat.copy(alpha = 0.7f), Color.Transparent), at, node * 2.8f), node * 2.8f, at)
                        drawCircle(fillColor, node, at)
                    } else drawCircle(trackColor, node - 0.75.dp.toPx(), at, style = Stroke(1.5.dp.toPx()))
                }
                if (heat != null) drawCircle(Brush.radialGradient(listOf(heat.copy(alpha = 0.8f), Color.Transparent), knob, 17.dp.toPx()), 17.dp.toPx(), knob)
                drawCircle(fillColor.copy(alpha = 0.3f), 11.dp.toPx(), knob)
                drawCircle(knobColor ?: fillColor, 7.dp.toPx(), knob)
            }
        }
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
        color = onFillColor, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
    }
}
