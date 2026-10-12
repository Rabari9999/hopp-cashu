package space.sovereigncast.hopp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import space.sovereigncast.hopp.ui.theme.Hopp

/**
 * Hopp's logo symbol (docs/brand/logo/hopp-symbol.svg): a ring, the hop and the
 * orange landing dot. Not [HoppIcons.Hop] — that is the hop alone, which means
 * boost (ADR 0019). The ink follows the theme the way the brand files do: dark
 * on light, light on dark; the dot stays orange in both.
 */
@Composable
fun HoppSymbol(modifier: Modifier = Modifier, ink: Color = MaterialTheme.colorScheme.onSurface) {
    val dot = Hopp.colors.value
    Canvas(modifier) {
        // The SVG's 100-unit box, scaled to the smaller side.
        val u = minOf(size.width, size.height) / 100f
        val stroke = Stroke(width = 7f * u, cap = StrokeCap.Round)
        drawCircle(ink, radius = 12f * u, center = Offset(22f * u, 68f * u), style = stroke)
        drawPath(
            Path().apply { moveTo(22f * u, 47f * u); cubicTo(22f * u, 12f * u, 78f * u, 12f * u, 78f * u, 47f * u) },
            ink, style = stroke,
        )
        drawCircle(dot, radius = 12f * u, center = Offset(78f * u, 68f * u))
    }
}
