package space.sovereigncast.hopp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
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
    /** What the design does to now playing beyond its colours; nothing in most. */
    val player: PlayerLook? = null,
)

/**
 * A design's own hand on now playing — a trial with Glut (maintainer 2026-11-10): the light sits
 * where the controls are, not behind the top bar, and lives while something plays.
 */
@Immutable
data class PlayerLook(
    /** Now playing's ground in place of [HoppColors.glow]: whole while it plays, [rest] of it paused. */
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
)

/** What runs over Nostr: sign-in, comments, the Nostr contacts. Never on what pays: money stays [HoppColors.value]. */
@Immutable
data class NostrColors(val fill: Color, val onFill: Color, val text: Color, val tint: Color)

/**
 * A soft oval of light on the ground: its middle at [x] and [y] of the window plus [dy], faded out
 * at [reach] of its radii — a CSS radial gradient, as Sovereigncast's `background` token writes it.
 */
@Immutable
data class Glow(val color: Color, val x: Float, val y: Float, val dy: Dp, val radiusX: Dp, val radiusY: Dp, val reach: Float)

internal val LightHoppColors = HoppColors(value = BitcoinOrange, onValue = Ink, valueText = Color(0xFFA65500), progressTrack = Color(0xFF8A8984), ok = Color(0xFF2E7D32), page = Paper)
internal val DarkHoppColors = HoppColors(value = BitcoinOrange, onValue = Ink, valueText = BitcoinOrange, progressTrack = Color(0xFF6E6D68), ok = Color(0xFF81C784), page = Ink)

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
            Glow(Color(0x80F9D3A1), x = 0.5f, y = 0.86f, dy = 0.dp, radiusX = 490.dp, radiusY = 380.dp, reach = 0.72f),
            Glow(Color(0x80F9D3A1), x = 1.05f, y = 1.08f, dy = 0.dp, radiusX = 575.dp, radiusY = 410.dp, reach = 0.7f),
        ),
        rest = 0.6f, coverLight = Color(0x59965014),
    ),
)
internal val GlutDarkColors = HoppColors(
    value = BitcoinOrange, onValue = Color(0xFF1A1410), valueText = Color(0xFFF7A440), progressTrack = Color(0xFF8A7663), ok = Color(0xFF86C9A1),
    page = Color.Transparent,
    glow = listOf(
        Glow(Color(0x29D65A14), x = 1f, y = 1.1f, dy = 0.dp, radiusX = 900.dp, radiusY = 600.dp, reach = 0.7f),
        Glow(Color(0x61F7931A), x = 0.5f, y = 0f, dy = (-140).dp, radiusX = 1000.dp, radiusY = 560.dp, reach = 0.7f),
    ),
    // The fire burns below. No stronger than this: secondary text lies on it (DesignColorsTest).
    player = PlayerLook(
        glow = listOf(
            Glow(Color(0x3BF7931A), x = 0.5f, y = 0.86f, dy = 0.dp, radiusX = 450.dp, radiusY = 340.dp, reach = 0.72f),
            Glow(Color(0x66D63C14), x = 1.05f, y = 1.08f, dy = 0.dp, radiusX = 575.dp, radiusY = 410.dp, reach = 0.7f),
            Glow(Color(0x4DA0280A), x = -0.1f, y = 1.04f, dy = 0.dp, radiusX = 520.dp, radiusY = 330.dp, reach = 0.7f),
        ),
        rest = 0.55f, coverLight = Color(0x73E66E1E), playHalo = Color(0x99FFB464),
        fillFrom = Color(0xFFFFC98A), heat = Color(0xFFFFB464), sparks = Color(0xFFFFC98A),
    ),
)

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

// Orange stays money's: on the ice it stands out the more.
internal val ColdstorageLightColors = HoppColors(
    value = BitcoinOrange, onValue = Color(0xFF0D2133), valueText = Color(0xFF8F4700), progressTrack = Color(0xFF6A8299), ok = Color(0xFF2A7048),
    page = Color.Transparent,
    glow = listOf(Glow(Color(0xFFBFE3F7), x = 0.5f, y = 0f, dy = (-140).dp, radiusX = 1100.dp, radiusY = 460.dp, reach = 0.72f)),
    progressFill = Color(0xFF0B4F80),
)
internal val ColdstorageDarkColors = HoppColors(
    value = BitcoinOrange, onValue = Color(0xFF06101B), valueText = Color(0xFFF7A440), progressTrack = Color(0xFF6C88A2), ok = Color(0xFF86C9A1),
    page = Color.Transparent,
    glow = listOf(
        Glow(Color(0x217FD3E0), x = 1f, y = 1.1f, dy = 0.dp, radiusX = 900.dp, radiusY = 600.dp, reach = 0.7f),
        Glow(Color(0x612E8FC4), x = 0.5f, y = 0f, dy = (-140).dp, radiusX = 1000.dp, radiusY = 560.dp, reach = 0.7f),
    ),
    progressFill = Color(0xFFA6D6F4),
)

val LocalHoppColors = staticCompositionLocalOf { LightHoppColors }
