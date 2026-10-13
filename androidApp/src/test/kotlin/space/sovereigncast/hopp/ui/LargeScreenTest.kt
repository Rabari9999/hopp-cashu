package space.sovereigncast.hopp.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Hopp on screens other than a phone held upright (ADR 0020). On a Pixel
 * Tablet on its side, now playing was a full-width square cover with every
 * control below the edge; these check that the controls are on screen and
 * that the tabs move to a rail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LargeScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun has(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(text: String) = rule.waitUntil(10_000) { has(text) }
    private fun tap(text: String) = rule.onNode(hasText(text) and hasClickAction()).performClick()
    private val player get() = org.koin.mp.KoinPlatform.getKoin().get<space.sovereigncast.hopp.player.PlayerController>()

    /** Subscribe to the test show, play its newest episode and open now playing. */
    private fun openPlayer() {
        rule.setContent { App() }
        tap("Discover")
        rule.onNode(hasSetTextAction()).performTextInput("https://pod.test/feed.xml")
        tap("Open as feed"); waitFor("Subscribe")
        tap("Subscribe"); waitFor("✓ Subscribed")
        tap("Ep 2"); waitFor("Show notes")
        tap("Play")
        rule.waitUntil(10_000) { rule.waitForIdle(); player.current.value != null }
        player.expand()
        rule.waitUntil(10_000) { rule.onAllNodes(hasContentDescription("Close player")).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Play, the skips and the position are all within the window. */
    private fun controlsOnScreen() {
        rule.onNode(hasContentDescription("Playback position")).assertIsDisplayed()
        rule.onNode(hasContentDescription("Playback speed")).assertIsDisplayed()
        rule.onNode(hasContentDescription("Forward", substring = true)).assertIsDisplayed()
    }

    @Test @Config(qualifiers = "en-w1280dp-h800dp-land")
    fun nowPlayingFitsATabletOnItsSide() { openPlayer(); controlsOnScreen() }

    @Test @Config(qualifiers = "en-w891dp-h411dp-land")
    fun nowPlayingFitsAPhoneOnItsSide() { openPlayer(); controlsOnScreen() }

    @Test @Config(qualifiers = "en-w800dp-h1280dp-port")
    fun nowPlayingFitsATabletUpright() { openPlayer(); controlsOnScreen() }

    /** Wider than a phone held upright: the tabs sit in a rail on the left, not along the bottom. */
    @Test @Config(qualifiers = "en-w1280dp-h800dp-land")
    fun tabsMoveToARailOnATablet() {
        rule.setContent { App() }
        waitFor("Library")
        val tab = rule.onNode(hasText("Library") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        check(tab.right < root.width / 4 && tab.bottom < root.height * 0.9f) { "Library tab at $tab in $root" }
    }

    /** A phone held upright keeps the bar along the bottom. */
    @Test @Config(qualifiers = "en-w411dp-h891dp")
    fun aPhoneKeepsTheBottomBar() {
        rule.setContent { App() }
        waitFor("Library")
        val tab = rule.onNode(hasText("Library") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        check(tab.top > root.height * 0.8f) { "Library tab at $tab in $root" }
    }
}
