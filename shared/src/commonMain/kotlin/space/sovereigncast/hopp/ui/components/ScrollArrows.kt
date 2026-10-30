package space.sovereigncast.hopp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.scroll_left
import space.sovereigncast.hopp.resources.scroll_right

/**
 * A row scrolled sideways, with arrows at its ends while a mouse is over it and there is more that
 * way (UX review 2026-10-30: a mouse wheel scrolls the page, not the row, so with a mouse alone the
 * rest of a row was out of reach). A touch screen has no hover: it swipes as before.
 */
@Composable
fun SideScroll(state: ScrollableState, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier.hoverable(hover)) {
        content()
        // Most of what is in view: the card cut at the edge comes in whole.
        val step = with(LocalDensity.current) { (maxWidth * 0.8f).toPx() }
        if (hovered && state.canScrollBackward) Arrow(HoppIcons.ChevronLeft, stringResource(Res.string.scroll_left), Modifier.align(Alignment.CenterStart)) {
            scope.launch { state.animateScrollBy(-step) }
        }
        if (hovered && state.canScrollForward) Arrow(HoppIcons.ChevronRight, stringResource(Res.string.scroll_right), Modifier.align(Alignment.CenterEnd)) {
            scope.launch { state.animateScrollBy(step) }
        }
    }
}

@Composable
private fun Arrow(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.padding(horizontal = 6.dp).size(40.dp).shadow(4.dp, CircleShape).clip(CircleShape)
            .background(colors.surface).clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = colors.onSurface)
    }
}
