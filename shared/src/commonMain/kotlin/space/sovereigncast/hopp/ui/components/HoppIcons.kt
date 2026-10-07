package space.sovereigncast.hopp.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The few icons the playback spike needs, drawn on a 24-unit grid. A stand-in:
 * the app gets Material Symbols Rounded (ADR 0019) once the icon set is wired
 * up; the boost hop stays Hopp's own drawing.
 */
object HoppIcons {
    private fun icon(name: String, block: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    val Play = icon("play") { path(fill = SolidColor(Color.Black)) { moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close() } }
    val Pause = icon("pause") {
        path(fill = SolidColor(Color.Black)) { moveTo(7f, 5f); lineTo(10f, 5f); lineTo(10f, 19f); lineTo(7f, 19f); close() }
        path(fill = SolidColor(Color.Black)) { moveTo(14f, 5f); lineTo(17f, 5f); lineTo(17f, 19f); lineTo(14f, 19f); close() }
    }
    val ChevronLeft = icon("chevron_left") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round) { moveTo(14.5f, 6f); lineTo(8.5f, 12f); lineTo(14.5f, 18f) }
    }
    val ChevronRight = icon("chevron_right") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round) { moveTo(9.5f, 6f); lineTo(15.5f, 12f); lineTo(9.5f, 18f) }
    }
    val ChevronDown = icon("chevron_down") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round) { moveTo(6f, 9.5f); lineTo(12f, 15.5f); lineTo(18f, 9.5f) }
    }
    /** Circular arrow back (replay), the number is drawn by the caller. */
    val Replay = icon("replay") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(5.5f, 12f); arcTo(6.5f, 6.5f, 0f, true, false, 12f, 5.5f); lineTo(9f, 5.5f)
            moveTo(9f, 5.5f); lineTo(11f, 3f); moveTo(9f, 5.5f); lineTo(11f, 8f)
        }
    }
    val Forward = icon("forward") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(18.5f, 12f); arcTo(6.5f, 6.5f, 0f, true, true, 12f, 5.5f); lineTo(15f, 5.5f)
            moveTo(15f, 5.5f); lineTo(13f, 3f); moveTo(15f, 5.5f); lineTo(13f, 8f)
        }
    }
    /** The hop from the logo: boost (ADR 0019). */
    val Hop = icon("hop") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.6f, strokeLineCap = StrokeCap.Round) {
            moveTo(5.3f, 14.4f); curveTo(5.3f, 4.3f, 18.7f, 4.3f, 18.7f, 12.5f)
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(15.3f, 17.3f); arcTo(3.4f, 3.4f, 0f, true, false, 22.1f, 17.3f); arcTo(3.4f, 3.4f, 0f, true, false, 15.3f, 17.3f); close()
        }
    }
}
