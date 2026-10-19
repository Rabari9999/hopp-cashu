package space.sovereigncast.hopp.ui

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_ESCAPE
import android.view.KeyEvent.KEYCODE_SPACE
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A keyboard and a mouse (ADR 0020, step 3): an iPad with a keyboard, a Mac, a Chromebook. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "en-w1280dp-h800dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyboardAndPointerTest {
    @get:Rule val rule = createComposeRule()

    private fun has(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(text: String) = rule.waitUntil(10_000) { has(text) }
    private fun tap(text: String) = rule.onNode(hasText(text) and hasClickAction()).performClick()
    private val player get() = org.koin.mp.KoinPlatform.getKoin().get<space.sovereigncast.hopp.player.PlayerController>()
    private fun until(check: () -> Boolean) = rule.waitUntil(10_000) { rule.waitForIdle(); check() }
    private fun key(code: Int) = KeyEvent(android.view.KeyEvent(ACTION_DOWN, code))

    private fun play() {
        rule.setContent { App() }
        tap("Discover")
        rule.onNode(hasSetTextAction()).performTextInput("https://pod.test/feed.xml")
        tap("Open as feed"); waitFor("Subscribe")
        tap("Subscribe"); waitFor("✓ Subscribed")
        tap("Ep 2"); waitFor("Show notes")
        tap("Play")
        until { player.state.value.isPlaying }
    }

    @Test
    fun spacePausesAndPlays() {
        play()
        rule.onRoot().performKeyPress(key(KEYCODE_SPACE))
        until { !player.state.value.isPlaying }
        rule.onRoot().performKeyPress(key(KEYCODE_SPACE))
        until { player.state.value.isPlaying }
    }

    @Test
    fun anArrowSkips() {
        play()
        val before = player.state.value.positionMs
        rule.onRoot().performKeyPress(key(KEYCODE_DPAD_RIGHT))
        until { player.state.value.positionMs >= before + 10_000 }
    }

    /** A search field keeps its spaces: typing there does not pause. */
    @Test
    fun aSpaceInTheSearchFieldIsAnyOtherCharacter() {
        play()
        tap("Discover")
        val field = rule.onNode(hasSetTextAction())
        field.performClick()
        field.performKeyPress(key(KEYCODE_SPACE))
        rule.waitForIdle()
        check(player.state.value.isPlaying) { "a space typed into search paused the player" }
    }

    @Test
    fun escapeClosesNowPlaying() {
        play()
        player.expand()
        rule.waitUntil(10_000) { rule.onAllNodes(hasContentDescription("Close player")).fetchSemanticsNodes().isNotEmpty() }
        rule.onRoot().performKeyPress(key(KEYCODE_ESCAPE))
        until { !player.expanded.value }
    }

    /** A mouse has no long press: a right-click on the speed opens all speeds. */
    @Test
    fun aRightClickDoesWhatALongPressDoes() {
        play()
        player.expand()
        rule.waitUntil(10_000) { rule.onAllNodes(hasContentDescription("Playback speed")).fetchSemanticsNodes().isNotEmpty() }
        val speedBefore = player.state.value.speed
        rule.onNode(hasContentDescription("Playback speed")).performMouseInput { rightClick() }
        waitFor("Kept for this show. Default: 1× (Settings → Playback)")
        check(player.state.value.speed == speedBefore) { "the right-click also changed the speed, as a tap would" }
    }
}
