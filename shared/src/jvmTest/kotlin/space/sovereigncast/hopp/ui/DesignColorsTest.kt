package space.sovereigncast.hopp.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue
import space.sovereigncast.hopp.ui.theme.FLARE_LIFT
import space.sovereigncast.hopp.ui.theme.Glow
import space.sovereigncast.hopp.ui.theme.GlutDarkColors
import space.sovereigncast.hopp.ui.theme.GlutDarkScheme
import space.sovereigncast.hopp.ui.theme.GlutLightColors
import space.sovereigncast.hopp.ui.theme.GlutLightScheme
import space.sovereigncast.hopp.ui.theme.HoppColors
import space.sovereigncast.hopp.ui.theme.ColdstorageDarkColors
import space.sovereigncast.hopp.ui.theme.ColdstorageDarkScheme
import space.sovereigncast.hopp.ui.theme.ColdstorageLightColors
import space.sovereigncast.hopp.ui.theme.ColdstorageLightScheme
import space.sovereigncast.hopp.ui.theme.RelayDarkColors
import space.sovereigncast.hopp.ui.theme.RelayDarkScheme
import space.sovereigncast.hopp.ui.theme.RelayLightColors
import space.sovereigncast.hopp.ui.theme.RelayLightScheme

/** Glut, Relay and Coldstorage hold to what ADR 0019 asks of Hopp's own colours: text 4.5:1, a control's edge and the progress bar 3:1. */
class DesignColorsTest {
    private fun contrast(a: Color, b: Color): Float {
        val (x, y) = a.luminance() to b.luminance()
        return (max(x, y) + 0.05f) / (min(x, y) + 0.05f)
    }

    private fun holds(scheme: ColorScheme, hopp: HoppColors, name: String) {
        fun at(least: Float, what: String, a: Color, b: Color) =
            assertTrue(contrast(a, b) >= least, "$name: $what is ${contrast(a, b)}:1, at least $least:1 asked")
        for ((ground, on) in listOf("page" to scheme.background, "card" to scheme.surface, "tile" to scheme.surfaceVariant)) {
            at(4.5f, "text on the $ground", scheme.onSurface, on)
            at(4.5f, "secondary text on the $ground", scheme.onSurfaceVariant, on)
            at(4.5f, "orange text on the $ground", hopp.valueText, on)
        }
        at(4.5f, "the main action's label", scheme.onPrimary, scheme.primary)
        at(4.5f, "a chosen segment's label", scheme.onSecondaryContainer, scheme.secondaryContainer)
        at(4.5f, "an error", scheme.error, scheme.background)
        at(4.5f, "an error box's text", scheme.onErrorContainer, scheme.errorContainer)
        at(4.5f, "a label on orange", hopp.onValue, hopp.value)
        at(4.5f, "done", hopp.ok, scheme.background)
        at(3f, "a control's edge", scheme.outline, scheme.background)
        at(3f, "the progress bar", hopp.progressTrack, scheme.background)
        hopp.progressFill?.let { at(3f, "the heard part of the progress bar", it, scheme.background) }
        hopp.nostr?.let { nostr ->
            at(4.5f, "a label on Nostr's colour", nostr.onFill, nostr.fill)
            at(4.5f, "Nostr's text on the page", nostr.text, scheme.background)
            at(4.5f, "Nostr's text on the card", nostr.text, scheme.surface)
            at(4.5f, "Nostr's text on its tint", nostr.text, nostr.tint)
        }
    }

    /** [ground] under the design's now playing glow at its fullest, at a point of a window of [w] × [h] dp. */
    private fun lit(ground: Color, glows: List<Glow>, x: Float, y: Float, w: Float, h: Float): Color =
        glows.fold(ground) { under, glow ->
            val far = hypot((x - w * glow.x - glow.dx.value) / glow.radiusX.value, (y - h * glow.y - glow.dy.value) / glow.radiusY.value)
            glow.color.copy(alpha = glow.color.alpha * (1f - far / glow.reach).coerceAtLeast(0f)).compositeOver(under)
        }

    /**
     * Now playing's own light (PlayerLook) lies under text and controls: they hold on its brightest spot too.
     * The glow is set in dp from the window's foot, so a window that shows all of it stands for every smaller
     * one — and a boost's flare only lifts it, which the rows below the foot stand for.
     */
    private fun holdsLit(scheme: ColorScheme, hopp: HoppColors, name: String) {
        val look = hopp.player ?: error("$name has no player look")
        val w = 1600
        val h = 900
        for (glow in look.glow) {
            assertTrue(glow.x == 0.5f && glow.y == 1f, "$name: now playing's glow is set from the middle of the window's foot")
            assertTrue(glow.radiusX.value * glow.reach + kotlin.math.abs(glow.dx.value) < w / 2 && glow.radiusY.value * glow.reach - glow.dy.value < h, "$name: the test's window shows all of the glow")
        }
        for (x in 0..w step 8) for (y in 0..h + FLARE_LIFT.value.toInt() step 4) {
            val on = lit(scheme.background, look.glow, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat())
            fun at(least: Float, what: String, a: Color, ground: Color = on) =
                assertTrue(contrast(a, ground) >= least, "$name, now playing at $x/$y: $what is ${contrast(a, ground)}:1, at least $least:1 asked")
            at(4.5f, "text", scheme.onSurface)
            at(4.5f, "secondary text", scheme.onSurfaceVariant)
            at(4.5f, "orange text", hopp.valueText)
            at(3f, "a control's edge", scheme.outline)
            at(3f, "the progress bar", hopp.progressTrack)
            // What lets the glow through: the value bar and the chips.
            look.panel?.let { panel ->
                val bar = panel.compositeOver(on)
                at(4.5f, "text on the value bar", scheme.onSurface, bar)
                at(4.5f, "secondary text on the value bar", scheme.onSurfaceVariant, bar)
                at(4.5f, "orange text on the value bar", hopp.valueText, bar)
            }
            look.chip?.let { at(4.5f, "a chip's label", scheme.onSurface, it.compositeOver(on)) }
            // The grain only ever takes from the glow: nothing read on it loses by a speck.
            look.grain?.let { grain ->
                val speck = grain.compositeOver(on)
                for (a in listOf(scheme.onSurface, scheme.onSurfaceVariant, hopp.valueText, scheme.outline, hopp.progressTrack))
                    assertTrue(contrast(a, speck) >= contrast(a, on), "$name, now playing at $x/$y: a speck of grain takes contrast")
            }
        }
    }

    @Test fun glutsNowPlayingByDay() = holdsLit(GlutLightScheme, GlutLightColors, "Glut, light")
    @Test fun glutsNowPlayingByNight() = holdsLit(GlutDarkScheme, GlutDarkColors, "Glut, dark")

    @Test fun glutByDay() = holds(GlutLightScheme, GlutLightColors, "Glut, light")
    @Test fun glutByNight() = holds(GlutDarkScheme, GlutDarkColors, "Glut, dark")
    @Test fun relayByDay() = holds(RelayLightScheme, RelayLightColors, "Relay, light")
    @Test fun relayByNight() = holds(RelayDarkScheme, RelayDarkColors, "Relay, dark")
    @Test fun coldstorageByDay() = holds(ColdstorageLightScheme, ColdstorageLightColors, "Coldstorage, light")
    @Test fun coldstorageByNight() = holds(ColdstorageDarkScheme, ColdstorageDarkColors, "Coldstorage, dark")
}
