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

/** How wide running text may get: show notes, settings, the wallet — beyond this
 *  a line is too long to read (about 80 characters). */
val READABLE_WIDTH: Dp = 720.dp

/** A page of mostly text, centred and no wider than [READABLE_WIDTH] on a large screen;
 *  on a phone it fills the width as before. */
@Composable
fun Readable(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = READABLE_WIDTH).fillMaxHeight()) { content() }
    }
}
