package space.sovereigncast.hopp.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
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
 *  system unless the caller overrides it from the settings; [design]: Hopp's own, Glut, Relay or Coldstorage. */
@Composable
fun HoppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    design: String = Settings.DESIGN_HOPP,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalHoppColors provides when (design) {
        Settings.DESIGN_GLUT -> if (darkTheme) GlutDarkColors else GlutLightColors
        Settings.DESIGN_RELAY -> if (darkTheme) RelayDarkColors else RelayLightColors
        Settings.DESIGN_COLDSTORAGE -> if (darkTheme) ColdstorageDarkColors else ColdstorageLightColors
        else -> if (darkTheme) DarkHoppColors else LightHoppColors
    }) {
        MaterialTheme(
            colorScheme = when (design) {
                Settings.DESIGN_GLUT -> if (darkTheme) GlutDarkScheme else GlutLightScheme
                Settings.DESIGN_RELAY -> if (darkTheme) RelayDarkScheme else RelayLightScheme
                Settings.DESIGN_COLDSTORAGE -> if (darkTheme) ColdstorageDarkScheme else ColdstorageLightScheme
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
        // Within what it covers: a glow is wider than a small preview of it.
        clipRect { glows(glows) }
    }
}

private fun DrawScope.glows(glows: List<Glow>, strength: Float = 1f) {
    for (glow in glows) {
        val center = Offset(size.width * glow.x, size.height * glow.y + glow.dy.toPx())
        val radius = glow.radiusY.toPx()
        val color = glow.color.copy(alpha = glow.color.alpha * strength)
        // A circle's gradient, stretched to the oval: Compose has no elliptical one.
        scale(glow.radiusX.toPx() / radius, 1f, center) {
            drawCircle(Brush.radialGradient(0f to color, glow.reach to color.copy(alpha = 0f), center = center, radius = radius), radius, center)
        }
    }
}

/**
 * Now playing's ground: the design's [PlayerLook] where it has one, else [pageGround]. The glow comes
 * up while it is [playing] and, where things may move ([moving] — not under "reduce motion", ADR 0016),
 * breathes and throws sparks. Read while drawing only: nothing recomposes with it.
 */
@Composable
fun Modifier.playerGround(playing: Boolean, moving: Boolean): Modifier {
    val look = Hopp.colors.player ?: return pageGround()
    val ground = MaterialTheme.colorScheme.background
    val lit by animateFloatAsState(if (playing) 1f else 0f, tween(1200))
    val alive = playing && moving
    val breath = if (alive) rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse)) else null
    val drift = if (alive && look.sparks != null) rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing))) else null
    return drawBehind {
        drawRect(ground)
        clipRect {
            glows(look.glow, look.rest + (1f - look.rest) * lit * (breath?.let { 0.7f + 0.3f * it.value } ?: 1f))
            if (drift != null && look.sparks != null) sparks(look.sparks, drift.value, lit)
        }
    }
}

private const val SPARKS = 7

/** Each spark rises once in a round of [round], one after the other, drifting a little to its side. */
private fun DrawScope.sparks(color: Color, round: Float, strength: Float) {
    for (i in 0 until SPARKS) {
        val way = (round + i / SPARKS.toFloat()) % 1f
        val place = (i * 0.618034f) % 1f
        val at = Offset(
            size.width * (0.12f + 0.76f * place) + (place - 0.5f) * 60.dp.toPx() * way,
            size.height - 40.dp.toPx() - 330.dp.toPx() * way,
        )
        val alpha = strength * 0.9f * if (way < 0.12f) way / 0.12f else 1f - (way - 0.12f) / 0.88f
        drawCircle(color.copy(alpha = alpha * 0.35f), 4.dp.toPx(), at)
        drawCircle(color.copy(alpha = alpha), 1.5.dp.toPx(), at)
    }
}

/** The main button of something that runs over Nostr: Nostr's colour where the design has one. */
@Composable
fun nostrButtonColors(): ButtonColors = Hopp.colors.nostr
    ?.let { ButtonDefaults.buttonColors(containerColor = it.fill, contentColor = it.onFill) } ?: ButtonDefaults.buttonColors()

object Hopp {
    val colors: HoppColors
        @Composable @ReadOnlyComposable get() = LocalHoppColors.current
}
