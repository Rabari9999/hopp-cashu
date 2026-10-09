package space.sovereigncast.hopp.ui.components

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A page with a header above tabs that also follow a horizontal swipe
 * (device feedback 2026-10-09, as in Pocket Casts): scrolling a tab's list
 * first moves the header away and brings it back once the list is at its top
 * again; the tab row stays. Each tab is its own lazy list ([page]), so a show
 * with hundreds of episodes stays lazy.
 *
 * One tab shows no tab row — [singleLabel] puts its name there instead.
 */
@Composable
fun TabbedPage(
    tabs: List<String>,
    pager: PagerState,
    modifier: Modifier = Modifier,
    singleLabel: Boolean = false,
    header: @Composable () -> Unit,
    page: @Composable (index: Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var headerHeight by remember { mutableIntStateOf(0) }
    // 0 = the header in full, -headerHeight = scrolled away.
    var offset by remember { mutableFloatStateOf(0f) }
    fun move(dy: Float): Float {
        val next = (offset + dy).coerceIn(-headerHeight.toFloat(), 0f)
        val used = next - offset
        offset = next
        return used
    }
    val connection = remember {
        object : NestedScrollConnection {
            // Up: the header goes first, then the list.
            override fun onPreScroll(available: Offset, source: NestedScrollSource) =
                if (available.y < 0) Offset(0f, move(available.y)) else Offset.Zero
            // Down: the list first, the header once the list is at its top.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) =
                if (available.y > 0) Offset(0f, move(available.y)) else Offset.Zero
        }
    }
    val drag = rememberDraggableState { move(it) }

    Layout(
        contents = listOf(
            { Box(Modifier.draggable(drag, Orientation.Vertical)) { header() } },
            {
                when {
                    tabs.size > 1 -> SecondaryTabRow(selectedTabIndex = pager.currentPage.coerceAtMost(tabs.lastIndex), containerColor = MaterialTheme.colorScheme.background) {
                        tabs.forEachIndexed { i, label ->
                            Tab(selected = pager.currentPage == i, onClick = { scope.launch { pager.animateScrollToPage(i) } }, text = { Text(label) })
                        }
                    }
                    singleLabel && tabs.size == 1 -> Text(tabs[0], Modifier.padding(start = 16.dp, top = 12.dp), style = MaterialTheme.typography.titleMedium)
                    else -> Box(Modifier)
                }
            },
            { HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = tabs.size > 1, key = { tabs.getOrElse(it) { "$it" } }) { page(it) } },
        ),
        modifier = modifier.fillMaxSize().clipToBounds().nestedScroll(connection),
    ) { (h, t, p), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        val hp = h.first().measure(loose)
        val tp = t.first().measure(loose)
        val pagerHeight = (constraints.maxHeight - tp.height).coerceAtLeast(0)
        val pp = p.first().measure(constraints.copy(minHeight = pagerHeight, maxHeight = pagerHeight))
        headerHeight = hp.height
        layout(constraints.maxWidth, constraints.maxHeight) {
            val y = offset.roundToInt().coerceIn(-hp.height, 0)
            hp.place(0, y)
            tp.place(0, y + hp.height)
            pp.place(0, y + hp.height + tp.height)
        }
    }
}
