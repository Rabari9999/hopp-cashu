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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
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

/** [lift]: all of them that much higher, in px; [grown]: that many times their size. */
private fun DrawScope.glows(glows: List<Glow>, strength: Float = 1f, lift: Float = 0f, grown: Float = 1f) {
    for (glow in glows) {
        val center = Offset(size.width * glow.x + glow.dx.toPx() * grown, size.height * glow.y + glow.dy.toPx() * grown - lift)
        val radius = glow.radiusY.toPx() * grown
        val color = glow.color.copy(alpha = glow.color.alpha * strength)
        // A circle's gradient, stretched to the oval: Compose has no elliptical one.
        scale(glow.radiusX.toPx() * grown / radius, 1f, center) {
            drawCircle(Brush.radialGradient(0f to color, glow.reach to color.copy(alpha = 0f), center = center, radius = radius), radius, center)
        }
    }
}

/** The window a [PlayerLook.spread] look's glows are set for; in a larger one they are as much larger. `DesignColorsTest` does the same. */
internal val SPREAD_WINDOW = 390.dp

/**
 * Now playing's ground: the design's [PlayerLook] where it has one, else [pageGround]. The glow comes
 * up while it is [playing] and, where things may move ([moving] — not under "reduce motion", ADR 0016),
 * lives: Glut's breathes and throws sparks, Coldstorage's ice glints, pulses run through Relay's net.
 * [boost] runs once from 0 to 1 when a boost was paid: the light flares up and settles again. [sent]
 * likewise when a comment went out: a wave through the net. Read while drawing only: nothing recomposes
 * with them.
 */
@Composable
fun Modifier.playerGround(playing: Boolean, moving: Boolean, boost: () -> Float = { 0f }, sent: () -> Float = { 0f }): Modifier {
    val look = Hopp.colors.player ?: return pageGround()
    val ground = MaterialTheme.colorScheme.background
    val lit by animateFloatAsState(if (playing) 1f else 0f, tween(1200))
    val alive = playing && moving
    val breath = if (alive && look.material == null) rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse)) else null
    val drift = if (alive && look.sparks != null) rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing))) else null
    // A minute's round, which every glint and pulse fits into a whole number of times: it starts again unseen.
    val round = if (alive && look.material != null) rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(ROUND * 1000, easing = LinearEasing))) else null
    val grain = look.grain?.let { rememberGrain(it) }
    return drawWithCache {
        val grown = if (look.spread) maxOf(1f, size.minDimension / SPREAD_WINDOW.toPx()) else 1f
        val facets = (look.material as? Material.Facets)?.let { facets(it, grown) }
        val mesh = (look.material as? Material.Mesh)?.let { mesh(it, grown) }
        onDrawBehind {
            drawRect(ground)
            clipRect {
                val boosted = if (moving) boost() else 0f
                val flare = flare(boosted)
                val steady = look.rest + (1f - look.rest) * lit * (breath?.let { 0.7f + 0.3f * it.value } ?: 1f)
                // Up to the whole glow and a little higher, never brighter than it: what is read on it stays readable.
                glows(look.glow, steady + (1f - steady) * flare, if (look.spread) 0f else FLARE_LIFT.toPx() * flare, grown)
                facets?.draw(this, round?.value)
                mesh?.draw(this, round?.value, if (moving) sent() else 0f)
                if (grain != null) drawRect(grain)
                if (drift != null && look.sparks != null) sparks(look.sparks, drift.value, lit)
                if (boosted > 0f && look.sparks != null) burst(look.sparks, boosted)
            }
        }
    }
}

/** Seconds of a round of what lives on a material. */
private const val ROUND = 60

/** Numbers between 0 and 1, the same on every run. */
private class Dice(private var seed: Int) {
    fun next(): Float {
        seed = seed * 1664525 + 1013904223
        return (seed ushr 8) / 16777216f
    }
}

/** A facet of the ice: [share] of the fill at most, and how its glint goes — [turns] times a round, from [phase]. */
private class Facet(val path: Path, val share: Float, val turns: Int, val phase: Float)

private class FacetSheet(val material: Material.Facets, val facets: List<Facet>, val edges: Path, val edge: Float) {
    /** [round]: where a round of glinting stands, or null where nothing moves. */
    fun draw(scope: DrawScope, round: Float?) = with(scope) {
        for (facet in facets) {
            // Never more than its share: the glint only ever takes from it.
            val glint = if (round == null) 1f else 0.6f + 0.4f * sin(2 * PI.toFloat() * (round * facet.turns + facet.phase))
            drawPath(facet.path, material.fill.copy(alpha = material.fill.alpha * facet.share * glint))
        }
        drawPath(edges, material.edge, style = Stroke(edge))
    }
}

/** A grid of cells about [FACET] wide, its inner corners pushed about, each cell cut into two triangles one way or the other. */
private fun CacheDrawScope.facets(material: Material.Facets, grown: Float): FacetSheet {
    val dice = Dice(11)
    val cell = FACET.toPx() * grown
    val cols = ceil(size.width / cell).toInt().coerceAtLeast(1)
    val rows = ceil(size.height / cell).toInt().coerceAtLeast(1)
    val (w, h) = size.width / cols to size.height / rows
    val corners = List((rows + 1) * (cols + 1)) { i ->
        val (x, y) = i % (cols + 1) to i / (cols + 1)
        Offset(
            x * w + if (x % cols != 0) (dice.next() - 0.5f) * w * 0.7f else 0f,
            y * h + if (y % rows != 0) (dice.next() - 0.5f) * h * 0.7f else 0f,
        )
    }
    val edges = Path()
    val facets = buildList {
        fun facet(a: Int, b: Int, c: Int) {
            val path = Path().apply { moveTo(corners[a].x, corners[a].y); lineTo(corners[b].x, corners[b].y); lineTo(corners[c].x, corners[c].y); close() }
            edges.addPath(path)
            add(Facet(path, dice.next(), 6 + (dice.next() * 5).toInt(), dice.next()))
        }
        for (y in 0 until rows) for (x in 0 until cols) {
            val i = y * (cols + 1) + x
            val j = i + cols + 1
            if (dice.next() > 0.5f) { facet(i, i + 1, j); facet(i + 1, j + 1, j) } else { facet(i, i + 1, j + 1); facet(i, j + 1, j) }
        }
    }
    return FacetSheet(material, facets, edges, 0.6.dp.toPx())
}

private val FACET = 72.dp

private class Net(
    val material: Material.Mesh, val nodes: List<Offset>, val sizes: List<Float>, val lines: List<Pair<Int, Int>>,
    val line: Float, val dot: Float, val band: Float,
) {
    /** [round]: where a round of pulses stands, or null where nothing moves. [sent]: a wave's way from the foot, 0 to 1. */
    fun draw(scope: DrawScope, round: Float?, sent: Float) = with(scope) {
        for ((a, b) in lines) drawLine(material.line, nodes[a], nodes[b], line)
        for (i in nodes.indices) drawCircle(material.node, sizes[i], nodes[i])
        if (round != null) for (i in lines.indices step 4) {
            // Every fourth line carries a pulse, each at its own pace and start.
            val (a, b) = lines[i]
            val way = (round * PULSE_TURNS[(i / 4) % PULSE_TURNS.size] + i * 0.137f) % 1f
            val at = nodes[a] + (nodes[b] - nodes[a]) * way
            val alpha = minOf(1f, way / 0.15f, (1f - way) / 0.15f)
            drawCircle(material.pulse.copy(alpha = alpha * 0.3f), dot * 2.4f, at)
            drawCircle(material.pulse.copy(alpha = alpha), dot, at)
        }
        if (sent > 0f && sent < 1f) {
            // From the middle of the window's foot, where it was written, out to its far corners.
            val from = Offset(size.width / 2, size.height)
            val reach = hypot(size.width / 2, size.height) + band
            val front = reach * (1f - (1f - sent) * (1f - sent))
            fun lit(at: Offset): Float = (1f - abs((at - from).getDistance() - front) / band).coerceAtLeast(0f) * (1f - sent)
            for ((a, b) in lines) {
                val alpha = minOf(lit(nodes[a]), lit(nodes[b]))
                if (alpha > 0f) drawLine(material.pulse.copy(alpha = alpha), nodes[a], nodes[b], line * 2)
            }
            for (i in nodes.indices) {
                val alpha = lit(nodes[i])
                if (alpha <= 0f) continue
                drawCircle(material.pulse.copy(alpha = alpha * 0.3f), sizes[i] * 3.5f, nodes[i])
                drawCircle(material.pulse.copy(alpha = alpha), sizes[i] * 1.4f, nodes[i])
            }
        }
    }
}

/** How often a pulse runs its line in a round: every 3, 4 or 5 seconds. */
private val PULSE_TURNS = listOf(ROUND / 3, ROUND / 5, ROUND / 4)

/** Nodes strewn over the ground, about one to a square of [NODE_GROUND], each tied to its two nearest. */
private fun CacheDrawScope.mesh(material: Material.Mesh, grown: Float): Net {
    val dice = Dice(7)
    // One to a cell of a grid, anywhere in it: strewn, and still all over the ground.
    val cell = NODE_GROUND.toPx() * grown
    val columns = ceil(size.width / cell).toInt().coerceIn(2, 12)
    val rows = ceil(size.height / cell).toInt().coerceIn(2, 12)
    val nodes = List(columns * rows) { Offset((it % columns + dice.next()) * size.width / columns, (it / columns + dice.next()) * size.height / rows) }
    val count = nodes.size
    val lines = buildList {
        for (i in nodes.indices) for (j in nodes.indices.filter { it != i }.sortedBy { (nodes[it] - nodes[i]).getDistanceSquared() }.take(2)) {
            if (j to i !in this) add(i to j)
        }
    }
    return Net(material, nodes, List(count) { (1.5f + dice.next() * 1.5f).dp.toPx() }, lines, 0.7.dp.toPx(), 2.dp.toPx(), 90.dp.toPx() * grown)
}

private val NODE_GROUND = 96.dp

/**
 * The ground of the mini player and of the bar along a wide window's foot: the card's colour, and along
 * its foot the design's light where it lights now playing ([PlayerLook.bar]) — whole while it [playing],
 * the look's rest of it paused. It does not move: it is in view on every page.
 */
@Composable
fun Modifier.barGround(playing: Boolean): Modifier {
    val ground = MaterialTheme.colorScheme.surface
    val look = Hopp.colors.player
    val light = look?.bar ?: return background(ground)
    val lit by animateFloatAsState(if (playing) 1f else 0f, tween(1200))
    return drawBehind {
        drawRect(ground)
        clipRect {
            val foot = Offset(size.width / 2, size.height)
            val radius = size.height * 1.6f
            val color = light.copy(alpha = light.alpha * (look.rest + (1f - look.rest) * lit))
            scale(size.width * 0.6f / radius, 1f, foot) {
                drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), foot, radius), radius, foot)
            }
        }
    }
}

/** How far the glow leaps at the height of a flare; `DesignColorsTest` looks that far below the window's foot too. */
internal val FLARE_LIFT = 48.dp

/** Up within a tenth of the way, then slowly down. */
private fun flare(way: Float): Float = when {
    way <= 0f || way >= 1f -> 0f
    way < 0.1f -> way / 0.1f
    else -> (1f - (way - 0.1f) / 0.9f).let { it * it }
}

private const val GRAIN = 128

/** A tile of fine grain, a cell about a dp: half the cells clear, the others up to [color]. The same on every run. */
@Composable
private fun rememberGrain(color: Color): Brush {
    val cell = LocalDensity.current.density.roundToInt().coerceAtLeast(1)
    return remember(color, cell) {
        val tile = ImageBitmap(GRAIN * cell, GRAIN * cell)
        val canvas = Canvas(tile)
        val paint = Paint()
        var seed = 20261110
        for (y in 0 until GRAIN) for (x in 0 until GRAIN) {
            seed = seed * 1664525 + 1013904223
            val share = (seed ushr 24) / 255f
            if (share < 0.5f) continue
            paint.color = color.copy(alpha = color.alpha * (share - 0.5f) * 2f)
            canvas.drawRect(Rect(Offset(x * cell.toFloat(), y * cell.toFloat()), Size(cell.toFloat(), cell.toFloat())), paint)
        }
        ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    }
}

private const val SPARKS = 7

/** As wide as the fire itself, in the middle of the window's foot: where its sparks rise. */
private val HEARTH = 411.dp

/** Each spark rises once in a round of [round], one after the other, drifting a little to its side. */
private fun DrawScope.sparks(color: Color, round: Float, strength: Float) {
    val hearth = minOf(size.width, HEARTH.toPx())
    for (i in 0 until SPARKS) {
        val way = (round + i / SPARKS.toFloat()) % 1f
        val place = (i * 0.618034f) % 1f
        val at = Offset(
            size.width / 2 + hearth * 0.76f * (place - 0.5f) + (place - 0.5f) * 60.dp.toPx() * way,
            size.height - 40.dp.toPx() - 330.dp.toPx() * way,
        )
        val alpha = strength * 0.9f * if (way < 0.12f) way / 0.12f else 1f - (way - 0.12f) / 0.88f
        drawCircle(color.copy(alpha = alpha * 0.35f), 4.dp.toPx(), at)
        drawCircle(color.copy(alpha = alpha), 1.5.dp.toPx(), at)
    }
}

private const val BURST = 14

/** A boost's sparks: all at once from the middle of the window's foot, fanning out as they rise. */
private fun DrawScope.burst(color: Color, way: Float) {
    // Fast at first, slower as they climb.
    val risen = 1f - (1f - way) * (1f - way)
    val hearth = minOf(size.width, HEARTH.toPx())
    for (i in 0 until BURST) {
        val place = (i * 0.618034f) % 1f
        val at = Offset(
            size.width / 2 + hearth * (place - 0.5f) * (0.25f + 0.7f * risen),
            size.height - 30.dp.toPx() - (300.dp.toPx() + 160.dp.toPx() * ((i * 0.381966f) % 1f)) * risen,
        )
        val alpha = if (way < 0.08f) way / 0.08f else 1f - (way - 0.08f) / 0.92f
        drawCircle(color.copy(alpha = alpha * 0.35f), 5.dp.toPx(), at)
        drawCircle(color.copy(alpha = alpha), 1.8.dp.toPx(), at)
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
