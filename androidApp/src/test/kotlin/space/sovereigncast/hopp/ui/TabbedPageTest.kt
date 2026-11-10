package space.sovereigncast.hopp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import space.sovereigncast.hopp.ui.components.TabbedPage

/** A page with a header above its tabs, as a show's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "en-w411dp-h891dp")
class TabbedPageTest {
    @get:Rule val rule = createComposeRule()

    /** Device check 2026-10-23: a drag up that starts on the tab row moves the header away, as one on the
     *  header does — it did nothing, and with a shorter header the row lay where a swipe starts. */
    @Test
    fun aDragOnTheTabRowMovesTheHeader() {
        val tabs = listOf("Episodes", "Top episodes", "Community", "Boosts")
        rule.setContent {
            TabbedPage(tabs, rememberPagerState { tabs.size }, header = { Box(Modifier.fillMaxWidth().height(400.dp)) { BasicText("The header") } }) {
                LazyColumn { items(100) { i -> BasicText("Episode $i", Modifier.height(80.dp)) } }
            }
        }
        fun tabRow() = rule.onNode(hasText("Top episodes")).fetchSemanticsNode().boundsInRoot.top
        val before = tabRow()
        rule.onNode(hasText("Top episodes")).performTouchInput { down(center); repeat(10) { moveBy(Offset(0f, -40f)) }; up() }
        rule.waitForIdle()
        val after = tabRow()
        // The header went up by the drag, and the row with it.
        check(after < before - 100f) { "the header stayed: tab row at $before → $after" }
    }

    /** Maintainer 2026-11-10 (Brave): a mouse wheel moved the header away with the list, but once the list was at its
     *  top — scrolled back, or another tab's — the wheel no longer brought it back: no list took the scroll. */
    @Test
    fun theWheelBringsTheHeaderBackWhenTheListIsAtItsTop() {
        val tabs = listOf("Show notes", "Chapters")
        rule.setContent {
            TabbedPage(tabs, rememberPagerState { tabs.size }, header = { Box(Modifier.fillMaxWidth().height(400.dp)) { BasicText("The header") } }) {
                LazyColumn { items(100) { i -> BasicText("Line $i", Modifier.height(80.dp)) } }
            }
        }
        fun tabRow() = rule.onNode(hasText("Chapters")).fetchSemanticsNode().boundsInRoot.top
        val start = tabRow()
        // Down: the header goes, then the list scrolls.
        rule.onNode(hasText("Line 2")).performMouseInput { repeat(12) { scroll(1f) } }
        rule.waitForIdle()
        check(tabRow() < start - 300f) { "the header stayed: ${tabRow()}" }
        // Up: the list to its top, then the header back.
        rule.onRoot().performMouseInput { moveTo(center); repeat(60) { scroll(-1f) } }
        rule.waitForIdle()
        check(tabRow() >= start - 1f) { "the header did not come back: ${tabRow()} of $start" }
    }
}
