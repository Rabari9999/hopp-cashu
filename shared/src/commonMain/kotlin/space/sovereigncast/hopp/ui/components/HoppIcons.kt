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
    /** Arrow into a tray: download. */
    val Download = icon("download") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(12f, 4f); lineTo(12f, 15f); moveTo(7.5f, 10.5f); lineTo(12f, 15f); lineTo(16.5f, 10.5f)
            moveTo(5f, 19.5f); lineTo(19f, 19.5f)
        }
    }
    /** Tick on a tray: downloaded. */
    val Downloaded = icon("downloaded") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(7f, 10.5f); lineTo(10.5f, 14f); lineTo(17f, 7f); moveTo(5f, 19.5f); lineTo(19f, 19.5f)
        }
    }
    /** Three lines with a play mark: up next. */
    val Queue = icon("queue") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(4f, 6f); lineTo(16f, 6f); moveTo(4f, 11f); lineTo(16f, 11f); moveTo(4f, 16f); lineTo(11f, 16f)
        }
        path(fill = SolidColor(Color.Black)) { moveTo(14f, 13.5f); lineTo(20f, 17f); lineTo(14f, 20.5f); close() }
    }
    /** Crescent: sleep timer. */
    val Globe = icon("globe") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(12f, 3.5f); arcTo(8.5f, 8.5f, 0f, true, true, 11.99f, 3.5f)
            moveTo(12f, 3.5f); arcTo(4f, 8.5f, 0f, true, true, 11.99f, 3.5f)
            moveTo(3.5f, 12f); lineTo(20.5f, 12f)
        }
    }
    val Moon = icon("moon") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(14.5f, 3.5f); arcTo(8.5f, 8.5f, 0f, true, false, 20.5f, 15.5f); arcTo(7f, 7f, 0f, false, true, 14.5f, 3.5f); close()
        }
    }
    /** Two bars: drag to reorder. */
    val DragHandle = icon("drag_handle") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(5f, 9.5f); lineTo(19f, 9.5f); moveTo(5f, 14.5f); lineTo(19f, 14.5f)
        }
    }
    /** Four rounded squares: the library as cards. */
    val Grid = icon("grid") {
        listOf(4f to 4f, 13.5f to 4f, 4f to 13.5f, 13.5f to 13.5f).forEach { (x, y) ->
            path(fill = SolidColor(Color.Black)) {
                moveTo(x + 1.5f, y); lineTo(x + 5f, y); quadTo(x + 6.5f, y, x + 6.5f, y + 1.5f); lineTo(x + 6.5f, y + 5f)
                quadTo(x + 6.5f, y + 6.5f, x + 5f, y + 6.5f); lineTo(x + 1.5f, y + 6.5f); quadTo(x, y + 6.5f, x, y + 5f)
                lineTo(x, y + 1.5f); quadTo(x, y, x + 1.5f, y); close()
            }
        }
    }
    /** A speech bubble: comments on Nostr (the tour's Nostr page). */
    val Comment = icon("comment") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(6.5f, 4.5f); lineTo(17.5f, 4.5f); quadTo(20f, 4.5f, 20f, 7f); lineTo(20f, 13.5f); quadTo(20f, 16f, 17.5f, 16f)
            lineTo(10f, 16f); lineTo(5.5f, 19.5f); lineTo(5.5f, 16f); quadTo(4f, 15.6f, 4f, 13.5f); lineTo(4f, 7f); quadTo(4f, 4.5f, 6.5f, 4.5f); close()
            moveTo(8.5f, 9f); lineTo(15.5f, 9f); moveTo(8.5f, 12f); lineTo(13f, 12f)
        }
    }
    /** A speaker with a wave: the player's volume. */
    val Volume = icon("volume") {
        path(fill = SolidColor(Color.Black)) { moveTo(4f, 9f); lineTo(8f, 9f); lineTo(13f, 5f); lineTo(13f, 19f); lineTo(8f, 15f); lineTo(4f, 15f); close() }
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(16f, 9f); quadTo(18f, 12f, 16f, 15f)
            moveTo(18.5f, 6.5f); quadTo(22.5f, 12f, 18.5f, 17.5f)
        }
    }
    /** A speaker struck through: muted. */
    val VolumeOff = icon("volume_off") {
        path(fill = SolidColor(Color.Black)) { moveTo(4f, 9f); lineTo(8f, 9f); lineTo(13f, 5f); lineTo(13f, 19f); lineTo(8f, 15f); lineTo(4f, 15f); close() }
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) { moveTo(16f, 9.5f); lineTo(21f, 14.5f); moveTo(21f, 9.5f); lineTo(16f, 14.5f) }
    }
    /** A chevron up: open the player. */
    val ChevronUp = icon("chevron_up") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round) { moveTo(6f, 14.5f); lineTo(12f, 8.5f); lineTo(18f, 14.5f) }
    }
    /** A circle half filled: light or dark (Settings → Appearance). */
    val Contrast = icon("contrast") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f) {
            moveTo(12f, 4f); arcTo(8f, 8f, 0f, true, true, 12f, 20f); arcTo(8f, 8f, 0f, true, true, 12f, 4f); close()
        }
        path(fill = SolidColor(Color.Black)) { moveTo(12f, 4f); arcTo(8f, 8f, 0f, false, true, 12f, 20f); close() }
    }
    /** Two sheets, one over the other: copy. */
    val Copy = icon("copy") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(10f, 8f); lineTo(18f, 8f); quadTo(20f, 8f, 20f, 10f); lineTo(20f, 18f); quadTo(20f, 20f, 18f, 20f)
            lineTo(10f, 20f); quadTo(8f, 20f, 8f, 18f); lineTo(8f, 10f); quadTo(8f, 8f, 10f, 8f); close()
            moveTo(16f, 8f); lineTo(16f, 6f); quadTo(16f, 4f, 14f, 4f); lineTo(6f, 4f); quadTo(4f, 4f, 4f, 6f); lineTo(4f, 14f); quadTo(4f, 16f, 6f, 16f); lineTo(8f, 16f)
        }
    }
    /** Three sliders: arranging something (Home's sections). */
    val Tune = icon("tune") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(4f, 7f); lineTo(20f, 7f); moveTo(4f, 12f); lineTo(20f, 12f); moveTo(4f, 17f); lineTo(20f, 17f)
        }
        listOf(15f to 7f, 8f to 12f, 13f to 17f).forEach { (x, y) ->
            path(fill = SolidColor(Color.Black)) {
                moveTo(x - 2.2f, y); quadTo(x - 2.2f, y - 2.2f, x, y - 2.2f); quadTo(x + 2.2f, y - 2.2f, x + 2.2f, y)
                quadTo(x + 2.2f, y + 2.2f, x, y + 2.2f); quadTo(x - 2.2f, y + 2.2f, x - 2.2f, y); close()
            }
        }
    }
}
