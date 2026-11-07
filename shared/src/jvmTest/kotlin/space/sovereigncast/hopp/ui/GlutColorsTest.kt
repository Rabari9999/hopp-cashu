package space.sovereigncast.hopp.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue
import space.sovereigncast.hopp.ui.theme.GlutDarkColors
import space.sovereigncast.hopp.ui.theme.GlutDarkScheme
import space.sovereigncast.hopp.ui.theme.GlutLightColors
import space.sovereigncast.hopp.ui.theme.GlutLightScheme
import space.sovereigncast.hopp.ui.theme.HoppColors

/** Glut holds to what ADR 0019 asks of Hopp's own colours: text 4.5:1, a control's edge and the progress bar 3:1. */
class GlutColorsTest {
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
    }

    @Test fun byDay() = holds(GlutLightScheme, GlutLightColors, "light")
    @Test fun byNight() = holds(GlutDarkScheme, GlutDarkColors, "dark")
}
