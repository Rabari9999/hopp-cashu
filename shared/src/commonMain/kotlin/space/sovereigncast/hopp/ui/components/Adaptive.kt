package space.sovereigncast.hopp.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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

/** From here the whole window has room for now playing as a column of its own on the right —
 *  every iPad on its side (the mini's 1133 pt, the Air's 1180), not only the Pro's 1210 and
 *  up (device feedback 2026-10-13: at 1200 an iPad opened it over the whole width). */
val PLAYER_PANE_WIDTH: Dp = 1000.dp

/** How much of the window now playing's column takes when it stands beside the content
 *  (device feedback 2026-10-13: 400 dp, ~30 % on a Pixel Tablet, felt narrow) … */
const val PLAYER_PANE_SHARE: Float = 0.4f

/** … and at least this much. */
val PLAYER_PANE_MIN: Dp = 400.dp

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

/**
 * A step back a page keeps for itself, outside the back stack — the show's
 * page showing a picked episode in one column (ADR 0020). The top bar's back
 * arrow takes it before leaving the page, as the system's back does through
 * the page's BackHandler; without it the arrow left the show while the
 * system's back only went back to it.
 */
class PageBack {
    var step: (() -> Unit)? by mutableStateOf(null)
}

val LocalPageBack = staticCompositionLocalOf { PageBack() }

/**
 * A title a page gives the top bar itself (maintainer 2026-10-27): a person's page shows the
 * name large in its head, and the bar takes it only once that has scrolled away — never twice.
 */
class PageTitle {
    var text: String? by mutableStateOf(null)
}

val LocalPageTitle = staticCompositionLocalOf { PageTitle() }
