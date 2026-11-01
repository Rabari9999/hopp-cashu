package space.sovereigncast.hopp.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
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
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

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
        // Now playing's own skip: the last one drawn. A tablet's mini player has one too,
        // covered when now playing opens over the whole window.
        rule.onAllNodes(hasContentDescription("Forward", substring = true)).onLast().assertIsDisplayed()
    }

    @Test @Config(qualifiers = "en-w1280dp-h800dp-land")
    fun nowPlayingFitsATabletOnItsSide() { openPlayer(); controlsOnScreen() }

    @Test @Config(qualifiers = "en-w891dp-h411dp-land")
    fun nowPlayingFitsAPhoneOnItsSide() { openPlayer(); controlsOnScreen() }

    @Test @Config(qualifiers = "en-w800dp-h1280dp-port")
    fun nowPlayingFitsATabletUpright() { openPlayer(); controlsOnScreen() }

    /** Step 2: on a tablet on its side a row of the show's page picks the episode
     *  beside it — the list stays, the page is not covered; until then the newest stands there. */
    @Test @Config(qualifiers = "en-w1280dp-h800dp-land")
    fun showAndEpisodeSideBySideOnATablet() {
        rule.setContent { App() }
        tap("Discover")
        rule.onNode(hasSetTextAction()).performTextInput("https://pod.test/feed.xml")
        tap("Open as feed"); waitFor("Subscribe")
        tap("Subscribe"); waitFor("✓ Subscribed")
        // The newest episode beside the list from the start, not an empty half (UX review 2026-10-30).
        waitFor("Show notes")
        check(!has("Pick an episode to see it here.")) { "the right half is empty" }
        tap("Ep 1"); rule.waitUntil(10_000) { rule.onAllNodesWithText("Ep 1").fetchSemanticsNodes().size == 2 }
        rule.onNode(hasText("Ep 2")).assertIsDisplayed()
        rule.onNode(hasText("✓ Subscribed")).assertIsDisplayed()
    }

    /** Upright, the same tablet keeps a page per step, as a phone does. */
    @Test @Config(qualifiers = "en-w800dp-h1280dp-port")
    fun anUprightTabletOpensTheEpisodeAsAPage() {
        rule.setContent { App() }
        tap("Discover")
        rule.onNode(hasSetTextAction()).performTextInput("https://pod.test/feed.xml")
        tap("Open as feed"); waitFor("Subscribe")
        tap("Subscribe"); waitFor("✓ Subscribed")
        tap("Ep 2"); waitFor("Show notes")
        check(!has("✓ Subscribed")) { "the show's page is still there" }
    }

    /** Step 2: wide enough, now playing stands beside the content — the rail and
     *  the page stay in reach. The episode that was picked on the right moves to
     *  the left rather than giving way to the show (device feedback 2026-10-13). */
    @Test @Config(qualifiers = "en-w1280dp-h800dp-land")
    fun nowPlayingStandsBesideTheEpisodeOnATablet() {
        openPlayer(); controlsOnScreen()
        rule.onNode(hasText("Library") and hasClickAction()).assertIsDisplayed()
        // The notes' own text twice: on the episode's page, and in the player's notes tab, which a
        // tablet's column has the height for (UX review 2026-10-30).
        check(notesShown() == 2) { "the notes on the page and in the player: ${notesShown()}" }
        check(!has("✓ Subscribed")) { "the show took the episode's place" }
    }

    /** Every iPad on its side gets the column too, not only the Pro (device feedback
     *  2026-10-13): the Air's 1180 pt and the mini's 1133. */
    @Test @Config(qualifiers = "en-w1180dp-h820dp-land")
    fun nowPlayingStandsBesideTheContentOnAnIpadAir() {
        openPlayer(); controlsOnScreen()
        rule.onNode(hasText("Library") and hasClickAction()).assertIsDisplayed()
        // The notes on the episode's page and in the player's notes tab.
        check(notesShown() == 2) { "the notes on the page and in the player: ${notesShown()}" }
    }

    /** A Pixel Tablet on its side as the device has it, its bars drawn (Robolectric draws none): the tabs
     *  there too, and every control still on screen (review 2026-10-31). */
    @Test @Config(qualifiers = "en-w1280dp-h736dp-land")
    fun nowPlayingKeepsItsTabsWithATabletsBarsDrawn() {
        openPlayer(); controlsOnScreen()
        check(notesShown() == 2) { "the notes on the page and in the player: ${notesShown()}" }
    }

    @Test @Config(qualifiers = "en-w1133dp-h744dp-land")
    fun nowPlayingStandsBesideTheContentOnAnIpadMini() {
        openPlayer(); controlsOnScreen()
        rule.onNode(hasText("Library") and hasClickAction()).assertIsDisplayed()
    }

    private fun notesShown() = rule.onAllNodesWithText("Mehr bei X.", substring = true).fetchSemanticsNodes().size

    /** Back from that episode goes to its show, not out of it — the arrow as the system's back. */
    @Test @Config(qualifiers = "en-w1280dp-h800dp-land")
    fun theArrowGoesBackFromThePickedEpisodeToItsShow() {
        openPlayer()
        rule.onNode(hasContentDescription("Back")).performClick()
        waitFor("✓ Subscribed")
        // Only the player's notes tab still shows them.
        check(notesShown() == 1) { "the episode is still there" }
    }

    @Test @Config(qualifiers = "en-w1280dp-h800dp-land")
    fun theSystemBackGoesBackFromThePickedEpisodeToItsShow() {
        openPlayer()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor("✓ Subscribed")
        // Only the player's notes tab still shows them.
        check(notesShown() == 1) { "the episode is still there" }
    }

    /** Now playing's column takes about 40 % of a Pixel Tablet's width (device feedback 2026-10-13). */
    @Test @Config(qualifiers = "en-w1280dp-h800dp-land")
    fun nowPlayingTakesFortyPercentOfATablet() {
        openPlayer()
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val close = rule.onNode(hasContentDescription("Close player")).fetchSemanticsNode().boundsInRoot
        val share = (root.right - close.left) / root.width
        check(share in 0.36f..0.44f) { "now playing starts at ${close.left} of ${root.width}" }
    }

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
