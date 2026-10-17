package space.sovereigncast.hopp.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.image_close
import space.sovereigncast.hopp.ui.canBlur

/**
 * A picture in a square without cutting it (device feedback 2026-10-17: chapter
 * images in 16:9 lost their sides): whole, with itself blurred behind it
 * filling the rest, as Apple Podcasts does — where blur cannot be drawn
 * (Android before 12), the page's colour instead. A square one fills it as before.
 */
@Composable
fun FittedImage(model: Any?, modifier: Modifier = Modifier, blur: Dp = 28.dp) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (canBlur) {
            AsyncImage(model, null, Modifier.fillMaxSize().blur(blur, BlurredEdgeTreatment.Rectangle), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f)))
        }
        AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

/**
 * A picture full screen (device feedback 2026-10-17), on black: pinch to zoom,
 * a double tap for 2.5× and back, moved about while zoomed; closed with the
 * cross, back, or — not zoomed — a swipe down.
 */
@Composable
fun ImageViewer(model: Any?, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val scope = rememberCoroutineScope()
        val closeAt = with(LocalDensity.current) { 120.dp.toPx() }
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val drop = remember { Animatable(0f) }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1f - drop.value / (closeAt * 3)).coerceIn(0.4f, 1f)))) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            fun clamp(o: Offset, s: Float) = Offset(
                o.x.coerceIn(-w * (s - 1) / 2, w * (s - 1) / 2),
                o.y.coerceIn(-h * (s - 1) / 2, h * (s - 1) / 2),
            )
            AsyncImage(
                model, null,
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { at ->
                            if (scale > 1f) { scale = 1f; offset = Offset.Zero }
                            else { scale = 2.5f; offset = clamp((Offset(w / 2, h / 2) - at) * 1.5f, 2.5f) }
                        })
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var dragging = false
                            do {
                                val event = awaitPointerEvent()
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val pointers = event.changes.count { it.pressed }
                                // The last event lifts every finger: no centroid then (NaN), and an
                                // offset from it took the picture away (device feedback 2026-10-17).
                                val centroid = event.calculateCentroid(useCurrent = true)
                                if (pointers == 0 || !centroid.isSpecified) continue
                                if (pointers > 1 || scale > 1f) {
                                    val next = (scale * zoom).coerceIn(1f, 5f)
                                    // Zoom about the fingers: the point under them stays put.
                                    val focus = centroid - Offset(w / 2, h / 2)
                                    val moved = clamp((offset - focus) * (next / scale) + focus + pan, next)
                                    if (moved.isValid()) offset = moved
                                    scale = next
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                } else if (pan.y != 0f && (dragging || pan.y > 0f)) {
                                    dragging = true
                                    scope.launch { drop.snapTo((drop.value + pan.y).coerceAtLeast(0f)) }
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                            if (dragging) {
                                if (drop.value > closeAt) onDismiss() else scope.launch { drop.animateTo(0f) }
                            }
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y + drop.value
                    },
                contentScale = ContentScale.Fit,
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp),
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f), contentColor = Color.White),
            ) { Icon(Icons.Rounded.Close, stringResource(Res.string.image_close)) }
        }
    }
}
