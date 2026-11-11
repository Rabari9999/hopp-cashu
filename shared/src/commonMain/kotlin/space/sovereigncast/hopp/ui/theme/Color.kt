package space.sovereigncast.hopp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Colour roles of ADR 0019. Contrast was measured there; change a value only
// together with that table. Glut's are Sovereigncast's theme of that name
// (podcasting_tools, podcast_manager/themes/glut/tokens.json), taken over by hand.

internal val Ink = Color(0xFF0A0A0A)
internal val Paper = Color(0xFFF6F5F2)
internal val BitcoinOrange = Color(0xFFF7931A)

internal val LightScheme: ColorScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Paper,
    secondary = Ink,
    onSecondary = Paper,
    background = Paper,
    onBackground = Ink,
    surface = Color(0xFFFFFFFF),
    onSurface = Ink,
    surfaceVariant = Color(0xFFECEBE7),
    onSurfaceVariant = Color(0xFF5E5D59),
    // outline: boundaries of controls (switch off, text field, chip) — ≥ 3:1
    // (WCAG 1.4.11). outlineVariant: decoration only, hairlines and dividers.
    outline = Color(0xFF7A7974),
    outlineVariant = Color(0xFFD6D4CE),
    // Neutral containers — left unset, Material's baseline tints menus,
    // sheets and the switch track purple.
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFBFAF8),
    surfaceContainerHigh = Color(0xFFF1F0EC),
    surfaceContainerHighest = Color(0xFFECEBE7),
    // The selected item's pill in the navigation rail and bar, a chosen segment, info boxes —
    // left unset, Material's baseline made them lavender (UX review 2026-10-30). Neutral, as
    // orange is kept for money (ADR 0019).
    secondaryContainer = Color(0xFFE2E0DA),
    onSecondaryContainer = Ink,
)

internal val DarkScheme: ColorScheme = darkColorScheme(
    primary = Paper,
    onPrimary = Ink,
    secondary = Paper,
    onSecondary = Ink,
    background = Ink,
    onBackground = Paper,
    surface = Color(0xFF161615),
    onSurface = Paper,
    surfaceVariant = Color(0xFF232220),
    onSurfaceVariant = Color(0xFFA3A19B),
    outline = Color(0xFF7A7974),
    outlineVariant = Color(0xFF3A3935),
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = Color(0xFF161615),
    surfaceContainer = Color(0xFF1C1C1A),
    surfaceContainerHigh = Color(0xFF232220),
    surfaceContainerHighest = Color(0xFF2A2927),
    secondaryContainer = Color(0xFF363531),
    onSecondaryContainer = Paper,
)

/**
 * The value layer — boost, streaming, sats. "Orange means money" (ADR 0019):
 * kept outside Material's scheme so no Material component turns orange by
 * accident. A label on [value] is always [onValue] (black); orange *text* uses
 * [valueText], which meets 4.5:1 on the light background.
 */
@Immutable
data class HoppColors(
    val value: Color,
    val onValue: Color,
    val valueText: Color,
    /** Unplayed part of the progress bar: ≥ 3:1 against the background
     *  (WCAG non-text contrast) and clearly apart from the played part. */
    val progressTrack: Color,
    /** "Done" on the privacy page's checks — green, ≥ 4.5:1 as text. */
    val ok: Color,
    /** What a page, its bars and its rows paint: the ground itself, or nothing where the ground
     *  glows ([glow]) and has to show through. The ground is [pageGround]'s. */
    val page: Color,
    /** The ground's glow, one for the whole window; none in Hopp's own design. */
    val glow: List<Glow> = emptyList(),
    /** A colour of Nostr's own, where the design has one: what goes through Nostr looks as it always did elsewhere. */
    val nostr: NostrColors? = null,
    /** The heard part of a progress bar, where the design gives it a colour; else the text's. */
    val progressFill: Color? = null,
    /** What the design does to now playing beyond its colours; nothing in Hopp's own. */
    val player: PlayerLook? = null,
) {
    val shapes: PlayerShapes get() = player?.shapes ?: PlayerShapes.Round
}

/**
 * A design's own hand on now playing — Glut's first, after a trial (maintainer 2026-11-10): the light sits
 * where the controls are, not behind the top bar, and lives while something plays. Then Coldstorage's
 * and Relay's, each with a material, forms and a progress bar of its own.
 */
@Immutable
data class PlayerLook(
    /** Now playing's ground in place of [HoppColors.glow]: whole while it plays, [rest] of it paused. Set in dp
     *  from the middle of the window's foot, so it is one picture in every window — a phone, a phone on its
     *  side, the column beside the content — and what holds on it in one holds in all. */
    val glow: List<Glow>,
    val rest: Float,
    /** The light the cover throws below itself. */
    val coverLight: Color,
    /** A halo round the play button; none where the ground is too bright to show one. */
    val playHalo: Color? = null,
    /** The heard part of the progress bar runs from this to its own colour. Never orange: that is money's. */
    val fillFrom: Color? = null,
    /** A shimmer along the heard part and round its knob. */
    val heat: Color? = null,
    /** Sparks rising from the window's foot while it plays. */
    val sparks: Color? = null,
    /** A fine grain over the ground. Towards the ground's own end — dark by night, light by day — so it
     *  only ever takes from the glow and what is read on it loses nothing. */
    val grain: Color? = null,
    /** The value bar's ground, letting the glow through. */
    val panel: Color? = null,
    /** The ground of the chips below the controls. */
    val chip: Color? = null,
    /** The same light, small: along the foot of the mini player and of the bar along a wide window's foot,
     *  over the card's colour. */
    val bar: Color? = null,
    /** [glow] is set for a phone and grows with the window, its place a share of it: light from the
     *  window's corners rather than a fire of one size at its foot. */
    val spread: Boolean = false,
    /** What the ground is made of, over its light. */
    val material: Material? = null,
    val shapes: PlayerShapes = PlayerShapes.Round,
    val progress: ProgressStyle = ProgressStyle.Gaps,
    /** The progress bar's knob, where it is not the heard part's colour. */
    val knob: Color? = null,
    /** A hairline round the cover. */
    val coverEdge: Color? = null,
    /** The play button's ground from its upper left to its lower right, in place of the main action's colour;
     *  what is on it stays that action's label colour. */
    val playFill: List<Color>? = null,
    /** Rings the play button sends out while it plays. */
    val rings: Color? = null,
    /** The value bar's edge, in place of a hairline. */
    val panelEdge: Brush? = null,
    /** An edge round the chips. */
    val chipEdge: Brush? = null,
    /** A glow round the boost button, of money's own colour. */
    val boostGlow: Color? = null,
    /** What runs across the value bar when a boost was paid. */
    val frost: Color? = null,
    /** Times and the speed in figures of one width. */
    val mono: Boolean = false,
    /** A paid boost sends money's dot over an arc from the boost button once: the hop of Hopp's mark. */
    val hop: Boolean = false,
)

/** The forms of now playing; [Round] is "round and calm" (ADR 0019), which a design's own hand may leave. */
@Immutable
data class PlayerShapes(
    val cover: Shape = RoundedCornerShape(24.dp),
    /** The cover where it is small: the mini player, the bar along a wide window's foot. */
    val thumb: Shape = RoundedCornerShape(10.dp),
    val play: Shape = CircleShape,
    /** The value bar. */
    val panel: Shape = RoundedCornerShape(20.dp),
    val chip: Shape = RoundedCornerShape(50),
    val boost: Shape = RoundedCornerShape(50),
    /** The chapter's name on the cover, */
    val label: Shape = RoundedCornerShape(50),
    /** and the buttons beside it. */
    val button: Shape = CircleShape,
) {
    companion object {
        val Round = PlayerShapes()
    }
}

/** How the progress bar shows the chapters. */
enum class ProgressStyle {
    /** A bar, open where a chapter begins (ADR 0019). */
    Gaps,
    /** The same bar, its knob the hollow ring of Hopp's mark. */
    Ring,
    /** A hairline with a notch where a chapter begins, its knob a crystal. */
    Notches,
    /** A line from node to node: a chapter begins at each, and the heard ones are lit. */
    Nodes,
}

sealed interface Material {
    /** Facets of ice over the whole ground: each [fill] at most, [edge] between them. */
    @Immutable
    data class Facets(val fill: Color, val edge: Color) : Material

    /** A net over the whole ground: [node]s and [line]s between neighbours, [pulse]s running along some. */
    @Immutable
    data class Mesh(val line: Color, val node: Color, val pulse: Color) : Material

    /** Round the play button alone, not under the pages: a soft [light], fine rings in [line], and a [wave] going out while it plays. */
    @Immutable
    data class Waves(val line: Color, val wave: Color, val light: Color) : Material
}

/** What runs over Nostr: sign-in, comments, the Nostr contacts. Never on what pays: money stays [HoppColors.value]. */
@Immutable
data class NostrColors(val fill: Color, val onFill: Color, val text: Color, val tint: Color)

/**
 * A soft oval of light on the ground: its middle at [x] and [y] of the window plus [dy], faded out
 * at [reach] of its radii — a CSS radial gradient, as Sovereigncast's `background` token writes it.
 */
@Immutable
data class Glow(val color: Color, val x: Float, val y: Float, val dy: Dp, val radiusX: Dp, val radiusY: Dp, val reach: Float, val dx: Dp = 0.dp)

// Hopp stays black and white, round and calm; its own hand on now playing is the arcs of its mark round the
// play button, without a colour (maintainer 2026-11-11).
internal val LightHoppColors = HoppColors(
    value = BitcoinOrange, onValue = Ink, valueText = Color(0xFFA65500), progressTrack = Color(0xFF8A8984), ok = Color(0xFF2E7D32), page = Paper,
    player = PlayerLook(
        glow = emptyList(), rest = 0.6f, coverLight = Color(0x40000000),
        material = Material.Waves(line = Color(0x1A0A0A0A), wave = Color(0x590A0A0A), light = Color(0xFFFFFFFF)),
        progress = ProgressStyle.Ring, panel = Color(0xC7FFFFFF), chip = Color(0xB3ECEBE7), hop = true,
    ),
)
internal val DarkHoppColors = HoppColors(
    value = BitcoinOrange, onValue = Ink, valueText = BitcoinOrange, progressTrack = Color(0xFF6E6D68), ok = Color(0xFF81C784), page = Ink,
    player = PlayerLook(
        glow = emptyList(), rest = 0.6f, coverLight = Color(0x38FFFFFF),
        material = Material.Waves(line = Color(0x1FF6F5F2), wave = Color(0x66F6F5F2), light = Color(0x14FFFFFF)),
        progress = ProgressStyle.Ring, panel = Color(0xC7161615), chip = Color(0xB3232220), hop = true,
    ),
)

/** Glut by day: cream and espresso under a warm glow, like the Sovereigncast logo on paper. */
internal val GlutLightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF3B2516),
    onPrimary = Color(0xFFFFF6EA),
    secondary = Color(0xFF3B2516),
    onSecondary = Color(0xFFFFF6EA),
    background = Color(0xFFFAF1E3),
    onBackground = Color(0xFF2A1D13),
    surface = Color(0xFFFFFCF7),
    onSurface = Color(0xFF2A1D13),
    surfaceVariant = Color(0xFFF1E2CD),
    onSurfaceVariant = Color(0xFF6B5746),
    outline = Color(0xFF8C7561),
    outlineVariant = Color(0xFFE6D1B8),
    surfaceContainerLowest = Color(0xFFFFFCF7),
    surfaceContainerLow = Color(0xFFFFFCF7),
    surfaceContainer = Color(0xFFFCF5EA),
    surfaceContainerHigh = Color(0xFFF4E7D4),
    surfaceContainerHighest = Color(0xFFF1E2CD),
    secondaryContainer = Color(0xFFECD6B9),
    onSecondaryContainer = Color(0xFF2A1D13),
    error = Color(0xFFAD3A43),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBE5E3),
    onErrorContainer = Color(0xFF862A31),
)

/** Glut by night, "Kaminfeuer": a deep ground, embers at its edge, candlelight for the main action. */
internal val GlutDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFFFE1BA),
    onPrimary = Color(0xFF2A1405),
    secondary = Color(0xFFFFE1BA),
    onSecondary = Color(0xFF2A1405),
    background = Color(0xFF0C0806),
    onBackground = Color(0xFFF6ECE0),
    surface = Color(0xFF1D1510),
    onSurface = Color(0xFFF6ECE0),
    surfaceVariant = Color(0xFF2C2018),
    onSurfaceVariant = Color(0xFFB9A693),
    outline = Color(0xFF8A7663),
    outlineVariant = Color(0xFF553722),
    surfaceContainerLowest = Color(0xFF0C0806),
    surfaceContainerLow = Color(0xFF1D1510),
    surfaceContainer = Color(0xFF241A14),
    surfaceContainerHigh = Color(0xFF2C2018),
    surfaceContainerHighest = Color(0xFF3B2B1F),
    secondaryContainer = Color(0xFF3D2A1A),
    onSecondaryContainer = Color(0xFFFFE9CF),
    error = Color(0xFFEE9BA2),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF3A1E1F),
    onErrorContainer = Color(0xFFF3B5BA),
)

internal val GlutLightColors = HoppColors(
    // valueText a shade under Sovereigncast's #a65500, which is 4.47:1 on the tile "Now" lies on, and less in the glow.
    value = BitcoinOrange, onValue = Color(0xFF1C120A), valueText = Color(0xFFA05000), progressTrack = Color(0xFF8C7561), ok = Color(0xFF2A7048),
    page = Color.Transparent,
    glow = listOf(Glow(Color(0xFFF9D3A1), x = 0.5f, y = 0f, dy = (-140).dp, radiusX = 1100.dp, radiusY = 460.dp, reach = 0.72f)),
    // Half as strong as the window's glow: the show notes' orange time marks can lie on this one.
    player = PlayerLook(
        glow = listOf(
            Glow(Color(0x80F9D3A1), x = 0.5f, y = 1f, dy = (-125).dp, radiusX = 490.dp, radiusY = 380.dp, reach = 0.72f),
            Glow(Color(0x80F9D3A1), x = 0.5f, dx = 226.dp, y = 1f, dy = 71.dp, radiusX = 575.dp, radiusY = 410.dp, reach = 0.7f),
        ),
        rest = 0.6f, coverLight = Color(0x59965014),
        grain = Color(0x38FFFFFF), panel = Color(0xB3FFFCF7), chip = Color(0xB3FFFCF7),
        bar = Color(0xCCF9D3A1),
    ),
)
internal val GlutDarkColors = HoppColors(
    value = BitcoinOrange, onValue = Color(0xFF1A1410), valueText = Color(0xFFF7A440), progressTrack = Color(0xFF8A7663), ok = Color(0xFF86C9A1),
    page = Color.Transparent,
    glow = listOf(
        Glow(Color(0x29D65A14), x = 1f, y = 1.1f, dy = 0.dp, radiusX = 900.dp, radiusY = 600.dp, reach = 0.7f),
        Glow(Color(0x61F7931A), x = 0.5f, y = 0f, dy = (-140).dp, radiusX = 1000.dp, radiusY = 560.dp, reach = 0.7f),
    ),
    // The fire burns below. No stronger than this: a control's edge lies on it (DesignColorsTest).
    player = PlayerLook(
        glow = listOf(
            Glow(Color(0x3BF7931A), x = 0.5f, y = 1f, dy = (-125).dp, radiusX = 450.dp, radiusY = 340.dp, reach = 0.72f),
            Glow(Color(0x66D63C14), x = 0.5f, dx = 226.dp, y = 1f, dy = 71.dp, radiusX = 575.dp, radiusY = 410.dp, reach = 0.7f),
            Glow(Color(0x4DA0280A), x = 0.5f, dx = (-247).dp, y = 1f, dy = 36.dp, radiusX = 520.dp, radiusY = 330.dp, reach = 0.7f),
        ),
        rest = 0.55f, coverLight = Color(0x73E66E1E), playHalo = Color(0x99FFB464),
        fillFrom = Color(0xFFFFC98A), heat = Color(0xFFFFB464), sparks = Color(0xFFFFC98A),
        grain = Color(0x2E000000), panel = Color(0xCC1D1510), chip = Color(0x17FFE1BA),
        // Embers, redder than the fire: no brighter, or the progress bar's track is lost on them.
        bar = Color(0x47D63C14),
    ),
)

/** Round Relay's value bar: violet into Nostr's magenta, fading out. */
private val RelayEdge = Brush.horizontalGradient(0f to Color(0xFF8B5CF6), 0.55f to Color(0xFFC026D3), 1f to Color(0x26C026D3))

/** Relay by day: lavender paper under a violet shimmer, purple for the main action. */
internal val RelayLightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF5224A3),
    onPrimary = Color(0xFFFBF7FF),
    secondary = Color(0xFF5224A3),
    onSecondary = Color(0xFFFBF7FF),
    background = Color(0xFFF1E7FC),
    onBackground = Color(0xFF24163D),
    surface = Color(0xFFFBF7FF),
    onSurface = Color(0xFF24163D),
    surfaceVariant = Color(0xFFE5D4F8),
    onSurfaceVariant = Color(0xFF57447A),
    outline = Color(0xFF7F6BA3),
    outlineVariant = Color(0xFFD6C3EF),
    surfaceContainerLowest = Color(0xFFFBF7FF),
    surfaceContainerLow = Color(0xFFFBF7FF),
    surfaceContainer = Color(0xFFF6EFFD),
    surfaceContainerHigh = Color(0xFFECE0FA),
    surfaceContainerHighest = Color(0xFFE5D4F8),
    secondaryContainer = Color(0xFFD9C1F9),
    onSecondaryContainer = Color(0xFF24163D),
    error = Color(0xFFAD3A43),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBE5E3),
    onErrorContainer = Color(0xFF862A31),
)

/** Relay by night: the night over the Nostr network, deep purple under a violet and magenta shimmer. */
internal val RelayDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFC2A8F5),
    onPrimary = Color(0xFF200A3A),
    secondary = Color(0xFFC2A8F5),
    onSecondary = Color(0xFF200A3A),
    background = Color(0xFF100B1D),
    onBackground = Color(0xFFF1EAFD),
    surface = Color(0xFF1B1430),
    onSurface = Color(0xFFF1EAFD),
    surfaceVariant = Color(0xFF281E42),
    onSurfaceVariant = Color(0xFFB3A7CF),
    outline = Color(0xFF8A7CAC),
    outlineVariant = Color(0xFF3F3362),
    surfaceContainerLowest = Color(0xFF100B1D),
    surfaceContainerLow = Color(0xFF1B1430),
    surfaceContainer = Color(0xFF211936),
    surfaceContainerHigh = Color(0xFF281E42),
    surfaceContainerHighest = Color(0xFF342A52),
    secondaryContainer = Color(0xFF352663),
    onSecondaryContainer = Color(0xFFEFE4FF),
    error = Color(0xFFEE9BA2),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF3A1E1F),
    onErrorContainer = Color(0xFFF3B5BA),
)

internal val RelayLightColors = HoppColors(
    value = BitcoinOrange, onValue = Color(0xFF130F24), valueText = Color(0xFF8F4700), progressTrack = Color(0xFF7F6BA3), ok = Color(0xFF2A7048),
    page = Color.Transparent,
    glow = listOf(Glow(Color(0xFFCBA8FA), x = 0.5f, y = 0f, dy = (-140).dp, radiusX = 1100.dp, radiusY = 460.dp, reach = 0.72f)),
    nostr = NostrColors(fill = Color(0xFF8739B8), onFill = Color(0xFFFFFFFF), text = Color(0xFF8739B8), tint = Color(0xFFF3E6F9)),
    progressFill = Color(0xFF5224A3),
    player = PlayerLook(
        glow = listOf(
            Glow(Color(0x80CBA8FA), x = 0.15f, y = -0.08f, dy = 0.dp, radiusX = 500.dp, radiusY = 330.dp, reach = 0.7f),
            Glow(Color(0x80E6B8F5), x = 1.05f, y = 1.04f, dy = 0.dp, radiusX = 460.dp, radiusY = 340.dp, reach = 0.7f),
        ),
        spread = true, rest = 0.7f, coverLight = Color(0x598B5CF6), coverEdge = Color(0x805224A3),
        material = Material.Mesh(line = Color(0x2E5224A3), node = Color(0x8C5224A3), pulse = Color(0xFF8739B8)),
        progress = ProgressStyle.Nodes, rings = Color(0xFF5224A3), mono = true,
        panel = Color(0xCCFBF7FF), panelEdge = RelayEdge, chip = Color.Transparent, chipEdge = SolidColor(Color(0xFF7F6BA3)),
        bar = Color(0x99CBA8FA),
    ),
)
internal val RelayDarkColors = HoppColors(
    value = BitcoinOrange, onValue = Color(0xFF130F24), valueText = Color(0xFFF7A440), progressTrack = Color(0xFF8A7CAC), ok = Color(0xFF86C9A1),
    page = Color.Transparent,
    glow = listOf(
        Glow(Color(0x24C026D3), x = 1f, y = 1.1f, dy = 0.dp, radiusX = 900.dp, radiusY = 600.dp, reach = 0.7f),
        Glow(Color(0x618B5CF6), x = 0.5f, y = 0f, dy = (-140).dp, radiusX = 1000.dp, radiusY = 560.dp, reach = 0.7f),
    ),
    nostr = NostrColors(fill = Color(0xFF8E3FC2), onFill = Color(0xFFFFFFFF), text = Color(0xFFE2B0FF), tint = Color(0xFF2F1846)),
    progressFill = Color(0xFFC2A8F5),
    // The net shows: violet from above, magenta from below, and between them the relays and their lines.
    player = PlayerLook(
        glow = listOf(
            Glow(Color(0x708B5CF6), x = 0.15f, y = -0.08f, dy = 0.dp, radiusX = 500.dp, radiusY = 330.dp, reach = 0.7f),
            Glow(Color(0x4CC026D3), x = 1.05f, y = 1.04f, dy = 0.dp, radiusX = 460.dp, radiusY = 340.dp, reach = 0.7f),
        ),
        spread = true, rest = 0.7f, coverLight = Color(0xB38B5CF6), coverEdge = Color(0x80C2A8F5), playHalo = Color(0x598B5CF6),
        material = Material.Mesh(line = Color(0x2EC2A8F5), node = Color(0x8CC2A8F5), pulse = Color(0xFFE879F9)),
        progress = ProgressStyle.Nodes, heat = Color(0xFFC2A8F5), rings = Color(0xFFC2A8F5), mono = true,
        panel = Color(0xCC1B1430), panelEdge = RelayEdge, chip = Color.Transparent, chipEdge = SolidColor(Color(0xFF8A7CAC)),
        bar = Color(0x478B5CF6),
    ),
)

/** Coldstorage by day, "Raureif" (maintainer 2026-11-09): snow under a pale light of ice, glacier blue for the main action. */
internal val ColdstorageLightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF0B4F80),
    onPrimary = Color(0xFFF8FBFE),
    secondary = Color(0xFF0B4F80),
    onSecondary = Color(0xFFF8FBFE),
    background = Color(0xFFEAF2F9),
    onBackground = Color(0xFF0D2133),
    surface = Color(0xFFF8FBFE),
    onSurface = Color(0xFF0D2133),
    surfaceVariant = Color(0xFFD9E6F2),
    onSurfaceVariant = Color(0xFF46607A),
    outline = Color(0xFF6A8299),
    outlineVariant = Color(0xFFC6D6E6),
    surfaceContainerLowest = Color(0xFFF8FBFE),
    surfaceContainerLow = Color(0xFFF8FBFE),
    surfaceContainer = Color(0xFFF1F6FB),
    surfaceContainerHigh = Color(0xFFE3EDF6),
    surfaceContainerHighest = Color(0xFFD9E6F2),
    secondaryContainer = Color(0xFFCDE1F2),
    onSecondaryContainer = Color(0xFF0D2133),
    error = Color(0xFFA3304A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBE5E9),
    onErrorContainer = Color(0xFF86283D),
)

/** Coldstorage by night, "Polarnacht": a ground of deep ice, glacier light above, a breath of turquoise below — no aurora green, it stays frost. */
internal val ColdstorageDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFA6D6F4),
    onPrimary = Color(0xFF05213A),
    secondary = Color(0xFFA6D6F4),
    onSecondary = Color(0xFF05213A),
    background = Color(0xFF06101B),
    onBackground = Color(0xFFE4F0FA),
    surface = Color(0xFF0D1A29),
    onSurface = Color(0xFFE4F0FA),
    surfaceVariant = Color(0xFF152639),
    onSurfaceVariant = Color(0xFF98B1C7),
    outline = Color(0xFF6C88A2),
    outlineVariant = Color(0xFF21374E),
    surfaceContainerLowest = Color(0xFF06101B),
    surfaceContainerLow = Color(0xFF0D1A29),
    surfaceContainer = Color(0xFF11202F),
    surfaceContainerHigh = Color(0xFF152639),
    surfaceContainerHighest = Color(0xFF1E3249),
    secondaryContainer = Color(0xFF163955),
    onSecondaryContainer = Color(0xFFE4F0FA),
    error = Color(0xFFF09DAB),
    onError = Color(0xFF5E1422),
    errorContainer = Color(0xFF3A1E24),
    onErrorContainer = Color(0xFFF3B5BF),
)

/** Cut, not round: Coldstorage leaves "round and calm" on now playing. */
private val IceShapes = PlayerShapes(
    cover = RoundedCornerShape(8.dp), thumb = RoundedCornerShape(4.dp), play = RoundedCornerShape(31), panel = RoundedCornerShape(10.dp),
    chip = RoundedCornerShape(7.dp), boost = RoundedCornerShape(7.dp), label = RoundedCornerShape(10.dp), button = RoundedCornerShape(6.dp),
)

/** Frosted glass by night: an edge that catches the light at its top. */
private val IceEdge = Brush.verticalGradient(listOf(Color(0x66E4F0FA), Color(0x21E4F0FA)))

// Orange stays money's: on the ice it stands out the more.
internal val ColdstorageLightColors = HoppColors(
    value = BitcoinOrange, onValue = Color(0xFF0D2133), valueText = Color(0xFF8F4700), progressTrack = Color(0xFF6A8299), ok = Color(0xFF2A7048),
    page = Color.Transparent,
    glow = listOf(Glow(Color(0xFFBFE3F7), x = 0.5f, y = 0f, dy = (-140).dp, radiusX = 1100.dp, radiusY = 460.dp, reach = 0.72f)),
    progressFill = Color(0xFF0B4F80),
    player = PlayerLook(
        glow = listOf(
            Glow(Color(0x80A9D8F3), x = 0.8f, y = -0.06f, dy = 0.dp, radiusX = 520.dp, radiusY = 360.dp, reach = 0.7f),
            Glow(Color(0x80CFE9F6), x = -0.05f, y = 1f, dy = 0.dp, radiusX = 420.dp, radiusY = 300.dp, reach = 0.7f),
        ),
        spread = true, rest = 0.75f, coverLight = Color(0x400B4F80), coverEdge = Color(0xB3FFFFFF), playHalo = Color(0x400B4F80),
        material = Material.Facets(fill = Color(0x59FFFFFF), edge = Color(0xB3FFFFFF)),
        shapes = IceShapes, progress = ProgressStyle.Notches, playFill = listOf(Color(0xFF1C79B4), Color(0xFF0B4F80)),
        panel = Color(0x8CFFFFFF), panelEdge = SolidColor(Color(0xE6FFFFFF)), chip = Color(0x8CFFFFFF), chipEdge = SolidColor(Color(0xE6FFFFFF)),
        boostGlow = Color(0x66F7931A), frost = Color(0xE6FFFFFF),
        bar = Color(0x8CA9D8F3),
    ),
)
internal val ColdstorageDarkColors = HoppColors(
    value = BitcoinOrange, onValue = Color(0xFF06101B), valueText = Color(0xFFF7A440), progressTrack = Color(0xFF6C88A2), ok = Color(0xFF86C9A1),
    page = Color.Transparent,
    glow = listOf(
        Glow(Color(0x217FD3E0), x = 1f, y = 1.1f, dy = 0.dp, radiusX = 900.dp, radiusY = 600.dp, reach = 0.7f),
        Glow(Color(0x612E8FC4), x = 0.5f, y = 0f, dy = (-140).dp, radiusX = 1000.dp, radiusY = 560.dp, reach = 0.7f),
    ),
    progressFill = Color(0xFFA6D6F4),
    // Ice: glacier light slanting in from above, facets, frosted glass — and orange the only warm thing on it.
    player = PlayerLook(
        glow = listOf(
            Glow(Color(0x4A2E8FC4), x = 0.8f, y = -0.06f, dy = 0.dp, radiusX = 520.dp, radiusY = 360.dp, reach = 0.7f),
            Glow(Color(0x1A7FD3E0), x = -0.05f, y = 1f, dy = 0.dp, radiusX = 420.dp, radiusY = 300.dp, reach = 0.7f),
        ),
        spread = true, rest = 0.7f, coverLight = Color(0xE6001428), coverEdge = Color(0x4DE4F0FA), playHalo = Color(0x732E8FC4),
        material = Material.Facets(fill = Color(0x0DFFFFFF), edge = Color(0x1FFFFFFF)),
        shapes = IceShapes, progress = ProgressStyle.Notches, knob = Color(0xFFFFFFFF), heat = Color(0xFFA6D6F4),
        playFill = listOf(Color(0xFFE3F4FE), Color(0xFF8CC4EA)),
        panel = Color(0x990D1A29), panelEdge = IceEdge, chip = Color(0x990D1A29), chipEdge = IceEdge,
        boostGlow = Color(0x8CF7931A), frost = Color(0x73E4F0FA),
        bar = Color(0x472E8FC4),
    ),
)

val LocalHoppColors = staticCompositionLocalOf { LightHoppColors }
