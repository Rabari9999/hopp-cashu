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
import space.sovereigncast.hopp.ui.theme.LightScheme
import space.sovereigncast.hopp.ui.theme.DarkScheme
import space.sovereigncast.hopp.ui.theme.LightHoppColors
import space.sovereigncast.hopp.ui.theme.DarkHoppColors
import space.sovereigncast.hopp.ui.theme.PAGE_SHARE
import space.sovereigncast.hopp.ui.theme.Material
import space.sovereigncast.hopp.ui.theme.SPREAD_WINDOW
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

    /** [ground] under the design's now playing glow at its fullest, at a point of a window of [w] × [h] dp; [grown] as the ground draws it. */
    private fun lit(ground: Color, glows: List<Glow>, x: Float, y: Float, w: Float, h: Float, grown: Float = 1f): Color =
        glows.fold(ground) { under, glow ->
            val far = hypot((x - w * glow.x - glow.dx.value * grown) / (glow.radiusX.value * grown), (y - h * glow.y - glow.dy.value * grown) / (glow.radiusY.value * grown))
            glow.color.copy(alpha = glow.color.alpha * (1f - far / glow.reach).coerceAtLeast(0f)).compositeOver(under)
        }

    /**
     * Now playing's own light (PlayerLook) lies under text and controls: they hold on its brightest spot too.
     * A glow set in dp from the window's foot is one picture everywhere, so a window that shows all of it stands
     * for every smaller one — and a boost's flare only lifts it, which the rows below the foot stand for. A glow
     * that grows with the window ([PlayerLook.spread]) is tried in windows of every kind instead.
     */
    private fun holdsLit(scheme: ColorScheme, hopp: HoppColors, name: String) {
        val look = hopp.player ?: error("$name has no player look")
        // The worst of each kind, so one run says all there is to mend.
        val worst = mutableMapOf<String, Pair<Float, String>>()
        val windows = if (look.spread) listOf(320 to 568, 390 to 844, 411 to 891, 891 to 411, 800 to 1280, 1280 to 800, 1600 to 900, 2560 to 1440) else listOf(1600 to 900)
        if (!look.spread) for (glow in look.glow) {
            val (w, h) = windows.single()
            assertTrue(glow.x == 0.5f && glow.y == 1f, "$name: now playing's glow is set from the middle of the window's foot")
            assertTrue(glow.radiusX.value * glow.reach + kotlin.math.abs(glow.dx.value) < w / 2 && glow.radiusY.value * glow.reach - glow.dy.value < h, "$name: the test's window shows all of the glow")
        }
        for ((w, h) in windows) {
            val grown = if (look.spread) max(1f, min(w, h) / SPREAD_WINDOW.value) else 1f
            val lift = if (look.spread) 0 else FLARE_LIFT.value.toInt()
            for (x in 0..w step max(8, w / 200)) for (y in 0..h + lift step max(4, h / 200)) {
                val light = lit(scheme.background, look.glow, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), grown)
                // What the ground is made of lies over its light: a facet at its brightest. Hairlines — a facet's
                // edge, the mesh — are no ground to read on, as little as a spark is.
                val grounds = listOfNotNull(light, (look.material as? Material.Facets)?.fill?.compositeOver(light), (look.material as? Material.Waves)?.light?.compositeOver(light))
                for (on in grounds) {
                    fun at(least: Float, what: String, a: Color, ground: Color = on) {
                        val has = contrast(a, ground)
                        if (has < least && has < (worst[what]?.first ?: least)) worst[what] = has to "$what is $has:1 in $w×$h at $x/$y, at least $least:1 asked"
                    }
                    at(4.5f, "text", scheme.onSurface)
                    at(4.5f, "secondary text", scheme.onSurfaceVariant)
                    at(4.5f, "orange text", hopp.valueText)
                    at(3f, "a control's edge", scheme.outline)
                    at(3f, "the progress bar", hopp.progressTrack)
                    at(3f, "the heard part of the progress bar", hopp.progressFill ?: scheme.onSurface)
                    look.knob?.let { at(3f, "the progress bar's knob", it) }
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
        }
        fun at(least: Float, what: String, a: Color, ground: Color) {
            val has = contrast(a, ground)
            if (has < least) worst[what] = has to "$what is $has:1, at least $least:1 asked"
        }
        // The same light, small, over the card: the mini player and the bar along a wide window's foot.
        look.bar?.let { bar ->
            val on = bar.compositeOver(scheme.surface)
            at(4.5f, "text on the bar's light", scheme.onSurface, on)
            at(4.5f, "secondary text on the bar's light", scheme.onSurfaceVariant, on)
            at(3f, "the progress bar on the bar's light", hopp.progressTrack, on)
            at(3f, "the heard part on the bar's light", hopp.progressFill ?: scheme.onSurface, on)
        }
        look.playFill?.forEach { at(3f, "the sign on the play button", scheme.onPrimary, it) }
        assertTrue(worst.isEmpty(), "$name, now playing:\n" + worst.values.joinToString("\n") { it.second })
    }

    /**
     * The pages lie on the window's glow and, since 2026-11-11, on [PAGE_SHARE] of now playing's material. A
     * facet over the glow takes nothing from what is read there that the glow alone leaves it.
     */
    private fun pagesHold(scheme: ColorScheme, hopp: HoppColors, name: String) {
        val fill = (hopp.player?.material as? Material.Facets)?.fill ?: return
        val facet = fill.copy(alpha = fill.alpha * PAGE_SHARE)
        for ((w, h) in listOf(320 to 568, 411 to 891, 891 to 411, 1280 to 800, 2560 to 1440)) for (x in 0..w step max(8, w / 100)) for (y in 0..h step max(4, h / 100)) {
            val glow = lit(scheme.background, hopp.glow, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat())
            val on = facet.compositeOver(glow)
            for ((least, a) in listOf(4.5f to scheme.onSurface, 4.5f to scheme.onSurfaceVariant, 4.5f to hopp.valueText, 3f to scheme.outline)) {
                val has = contrast(a, on)
                assertTrue(has >= least || has >= contrast(a, glow) - 0.15f, "$name, a page in $w×$h at $x/$y: a facet leaves $has:1, the glow alone ${contrast(a, glow)}:1")
            }
        }
    }

    @Test fun coldstoragesPagesByDay() = pagesHold(ColdstorageLightScheme, ColdstorageLightColors, "Coldstorage, light")
    @Test fun coldstoragesPagesByNight() = pagesHold(ColdstorageDarkScheme, ColdstorageDarkColors, "Coldstorage, dark")

    @Test fun hoppsNowPlayingByDay() = holdsLit(LightScheme, LightHoppColors, "Hopp, light")
    @Test fun hoppsNowPlayingByNight() = holdsLit(DarkScheme, DarkHoppColors, "Hopp, dark")
    @Test fun relaysNowPlayingByDay() = holdsLit(RelayLightScheme, RelayLightColors, "Relay, light")
    @Test fun relaysNowPlayingByNight() = holdsLit(RelayDarkScheme, RelayDarkColors, "Relay, dark")
    @Test fun coldstoragesNowPlayingByDay() = holdsLit(ColdstorageLightScheme, ColdstorageLightColors, "Coldstorage, light")
    @Test fun coldstoragesNowPlayingByNight() = holdsLit(ColdstorageDarkScheme, ColdstorageDarkColors, "Coldstorage, dark")
    @Test fun glutsNowPlayingByDay() = holdsLit(GlutLightScheme, GlutLightColors, "Glut, light")
    @Test fun glutsNowPlayingByNight() = holdsLit(GlutDarkScheme, GlutDarkColors, "Glut, dark")

    @Test fun glutByDay() = holds(GlutLightScheme, GlutLightColors, "Glut, light")
    @Test fun glutByNight() = holds(GlutDarkScheme, GlutDarkColors, "Glut, dark")
    @Test fun relayByDay() = holds(RelayLightScheme, RelayLightColors, "Relay, light")
    @Test fun relayByNight() = holds(RelayDarkScheme, RelayDarkColors, "Relay, dark")
    @Test fun coldstorageByDay() = holds(ColdstorageLightScheme, ColdstorageLightColors, "Coldstorage, light")
    @Test fun coldstorageByNight() = holds(ColdstorageDarkScheme, ColdstorageDarkColors, "Coldstorage, dark")
}
