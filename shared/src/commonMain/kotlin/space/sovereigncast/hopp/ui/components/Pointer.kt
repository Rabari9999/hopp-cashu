package space.sovereigncast.hopp.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/*
 * Keyboard and pointer (ADR 0020, step 3): an iPad with a keyboard, a Mac, a
 * Chromebook or a tablet with a mouse.
 */

/**
 * A right-click does what a long press does on a touch screen: share a chapter or
 * a transcript line, all speeds, the previous or next chapter. Without it a mouse
 * reaches none of these, since it has no long press.
 *
 * Put it before the clickable in the chain: it looks at the press in the initial
 * pass and consumes it, so the clickable does not also take it for a tap.
 */
fun Modifier.onSecondaryClick(action: (() -> Unit)?): Modifier =
    if (action == null) this else pointerInput(action) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    event.changes.forEach { it.consume() }
                    action()
                }
            }
        }
    }

/** What the keys do; each answers whether it took the key. */
class KeyShortcuts(
    val playPause: () -> Unit,
    val back: () -> Unit,
    val forward: () -> Unit,
    /** Escape: close what is open over the page (now playing); false if nothing is. */
    val close: () -> Boolean,
)

/**
 * Whether a text field holds the keyboard now; null where the platform cannot tell. In the
 * browser the page's own input element has the focus then.
 */
internal expect fun textFieldFocused(): Boolean?

/**
 * Space plays and pauses, ← and → skip, Escape closes now playing — wherever
 * the listener is, as in every desktop player.
 *
 * Only while nothing else holds the focus: a search field needs its spaces and
 * arrows, a focused button its space. The frame takes the focus itself, and
 * takes it back whenever it is left with nobody holding it — after a search
 * field is done, say. A focus target, not `focusable()`: it adds nothing a
 * screen reader would stop at.
 *
 * Where the platform tells when a text field has the keyboard (the browser), space
 * and the arrows play and skip whatever else holds the focus (maintainer 2026-11-09:
 * a button clicked last kept it in Hopp Web, and space skipped 10 seconds again).
 * Enter still presses a focused button.
 */
@Composable
fun Modifier.keyShortcuts(shortcuts: KeyShortcuts): Modifier {
    val requester = remember { FocusRequester() }
    var mine by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    return this
        .focusRequester(requester)
        .onFocusChanged { state ->
            mine = state.isFocused
            if (!state.hasFocus) runCatching { requester.requestFocus() }
        }
        .onPreviewKeyEvent { e ->
            if (mine || textFieldFocused() != false || e.isCtrlPressed || e.isMetaPressed || e.isAltPressed) return@onPreviewKeyEvent false
            val action = when (e.key) {
                Key.Spacebar -> shortcuts.playPause
                Key.DirectionLeft -> shortcuts.back
                Key.DirectionRight -> shortcuts.forward
                else -> return@onPreviewKeyEvent false
            }
            // Its key-up too: a button never pressed by the key-down is not clicked by it.
            if (e.type == KeyEventType.KeyDown) action()
            true
        }
        .onKeyEvent { e ->
            if (!mine || e.type != KeyEventType.KeyDown || e.isCtrlPressed || e.isMetaPressed || e.isAltPressed) return@onKeyEvent false
            when (e.key) {
                Key.Spacebar -> { shortcuts.playPause(); true }
                Key.DirectionLeft -> { shortcuts.back(); true }
                Key.DirectionRight -> { shortcuts.forward(); true }
                Key.Escape -> shortcuts.close()
                else -> false
            }
        }
        .focusTarget()
}
