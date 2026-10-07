package space.sovereigncast.hopp.ui.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The progress bar of the now-playing screen: chapter boundaries are gaps in
 * the track (ADR 0019). Tap or drag to seek; while dragging the knob follows
 * the finger and the seek happens on release.
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
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val shown = (dragFraction ?: fraction).coerceIn(0f, 1f)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .semantics { contentDescription = label }
            .pointerInput(Unit) { detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragFraction = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragFraction?.let(onSeek); dragFraction = null },
                    onDragCancel = { dragFraction = null },
                ) { change, _ -> dragFraction = (change.position.x / size.width).coerceIn(0f, 1f) }
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
}
