package space.sovereigncast.hopp.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import space.sovereigncast.hopp.data.Settings

// "Round and calm" (ADR 0019): generous radii echoing the round mark.
private val HoppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Brand colours only — no dynamic colour (ADR 0019). Light/dark follows the
 *  system unless the caller overrides it from the settings; [design]: Hopp's own or Glut. */
@Composable
fun HoppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    design: String = Settings.DESIGN_HOPP,
    content: @Composable () -> Unit,
) {
    val glut = design == Settings.DESIGN_GLUT
    CompositionLocalProvider(LocalHoppColors provides when {
        glut -> if (darkTheme) GlutDarkColors else GlutLightColors
        else -> if (darkTheme) DarkHoppColors else LightHoppColors
    }) {
        MaterialTheme(
            colorScheme = when {
                glut -> if (darkTheme) GlutDarkScheme else GlutLightScheme
                else -> if (darkTheme) DarkScheme else LightScheme
            },
            typography = hoppTypography(),
            shapes = HoppShapes,
            content = content,
        )
    }
}

/**
 * The ground under everything, with the design's glow: for what covers the whole window, or a preview
 * of it. One glow for all, so what lies on it paints [HoppColors.page] and not a ground of its own.
 */
@Composable
fun Modifier.pageGround(): Modifier {
    val ground = MaterialTheme.colorScheme.background
    val glows = Hopp.colors.glow
    return if (glows.isEmpty()) background(ground) else drawBehind {
        drawRect(ground)
        for (glow in glows) {
            val center = Offset(size.width * glow.x, size.height * glow.y + glow.dy.toPx())
            val radius = glow.radiusY.toPx()
            // A circle's gradient, stretched to the oval: Compose has no elliptical one.
            scale(glow.radiusX.toPx() / radius, 1f, center) {
                drawCircle(Brush.radialGradient(0f to glow.color, glow.reach to glow.color.copy(alpha = 0f), center = center, radius = radius), radius, center)
            }
        }
    }
}

object Hopp {
    val colors: HoppColors
        @Composable @ReadOnlyComposable get() = LocalHoppColors.current
}
