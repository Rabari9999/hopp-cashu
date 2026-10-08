package space.sovereigncast.hopp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertTrue

/** ADR 0019's contrast table as a test: text 4.5:1, controls 3:1 (WCAG
 *  1.4.3 / 1.4.11). A switch that is off was nearly invisible at 1.4:1. */
class ContrastTest {
    private fun ratio(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun check(name: String, scheme: ColorScheme, extra: HoppColors) {
        val failures = buildList {
            fun need(what: String, fg: Color, bg: Color, min: Double) { val r = ratio(fg, bg); if (r < min) add("$name $what ${"%.2f".format(r)}:1 < $min") }
            need("text", scheme.onBackground, scheme.background, 4.5)
            need("secondary text", scheme.onSurfaceVariant, scheme.background, 4.5)
            need("secondary text on surface", scheme.onSurfaceVariant, scheme.surface, 4.5)
            need("outline on background", scheme.outline, scheme.background, 3.0)
            need("outline on surface", scheme.outline, scheme.surface, 3.0)
            need("switch knob (off) on its track", scheme.outline, scheme.surfaceContainerHighest, 3.0)
            need("progress track", extra.progressTrack, scheme.background, 3.0)
            need("label on orange", extra.onValue, extra.value, 4.5)
            need("orange text", extra.valueText, scheme.background, 4.5)
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test fun light() = check("light", LightScheme, LightHoppColors)
    @Test fun dark() = check("dark", DarkScheme, DarkHoppColors)
}
