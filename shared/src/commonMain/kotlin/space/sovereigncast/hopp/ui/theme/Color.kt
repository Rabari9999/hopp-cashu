package space.sovereigncast.hopp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Colour roles of ADR 0019. Contrast was measured there; change a value only
// together with that table.

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
)

internal val LightHoppColors = HoppColors(value = BitcoinOrange, onValue = Ink, valueText = Color(0xFFA65500), progressTrack = Color(0xFF8A8984))
internal val DarkHoppColors = HoppColors(value = BitcoinOrange, onValue = Ink, valueText = BitcoinOrange, progressTrack = Color(0xFF6E6D68))

val LocalHoppColors = staticCompositionLocalOf { LightHoppColors }
