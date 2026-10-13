package space.sovereigncast.hopp.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Large screens (ADR 0020): the layout follows the window, never the device —
 * a tablet in split screen is a phone, a phone on its side is wide, a Mac
 * window is whatever the listener drags it to.
 */

/** From here the window is no phone held upright: a navigation rail instead of the bar. */
val MEDIUM_WIDTH: Dp = 600.dp

/** From here the content (beside the rail) holds a list and its detail side by side. */
val EXPANDED_WIDTH: Dp = 840.dp

/** From here the whole window has room for now playing as a column of its own on the right. */
val PLAYER_PANE_WIDTH: Dp = 1200.dp

/** How wide now playing's column is when it stands beside the content. */
val PLAYER_PANE: Dp = 400.dp

/** How wide a list of episodes may get: wider, the play button drifts away from the title. */
val LIST_WIDTH: Dp = 840.dp

/** How wide running text may get: show notes, settings, the wallet — beyond this
 *  a line is too long to read (about 80 characters). */
val READABLE_WIDTH: Dp = 720.dp

/** A page centred and no wider than [max] on a large screen — [READABLE_WIDTH] for
 *  running text, [LIST_WIDTH] for lists; on a phone it fills the width as before. */
@Composable
fun Readable(max: Dp = READABLE_WIDTH, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = max).fillMaxHeight()) { content() }
    }
}
