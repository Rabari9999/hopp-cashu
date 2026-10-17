package space.sovereigncast.hopp.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The library's shows as cards or a list (device feedback 2026-10-13): cards by
 * default, the list one tap away and kept; both carry the show's numbers — unheard
 * episodes on the card, the episodes and the unheard ones in the list — on a phone
 * as on a tablet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryViewTest {
    @get:Rule val rule = createComposeRule()

    private fun has(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(text: String) = rule.waitUntil(10_000) { has(text) }
    private fun tap(text: String) = rule.onNode(hasText(text) and hasClickAction()).performClick()
    private val settings get() = org.koin.mp.KoinPlatform.getKoin().get<space.sovereigncast.hopp.data.SettingsRepository>()

    /** Subscribe to the test show (Ep 1 from before, Ep 2 new) and open the library. */
    private fun library() {
        rule.setContent { App() }
        tap("Discover")
        rule.onNode(hasSetTextAction()).performTextInput("https://pod.test/feed.xml")
        tap("Open as feed"); waitFor("Subscribe")
        tap("Subscribe"); waitFor("✓ Subscribed")
        tap("Library"); waitFor("Test Show")
    }

    private fun cardsThenList() {
        library()
        rule.onNode(hasContentDescription("Cards")).assertIsSelected()
        rule.onNode(hasContentDescription("2 not heard yet")).assertIsDisplayed()
        check(!has("2 episodes")) { "the cards show the episode count" }
        rule.onNode(hasContentDescription("List")).performClick()
        waitFor("2 episodes")
        rule.onNode(hasContentDescription("2 not heard yet")).assertIsDisplayed()
        rule.waitUntil(10_000) { runBlocking { settings.settings.first().libraryAsList } }
    }

    @Test @Config(qualifiers = "en-w1280dp-h800dp-land")
    fun cardsByDefaultAndTheListOnATablet() = cardsThenList()

    @Test @Config(qualifiers = "en-w411dp-h891dp")
    fun cardsByDefaultAndTheListOnAPhone() = cardsThenList()

    /** The choice is kept: a library opened again is still a list. */
    @Test @Config(qualifiers = "en-w411dp-h891dp")
    fun theListIsKept() {
        runBlocking { settings.update { it.copy(libraryAsList = true) } }
        library()
        rule.onNode(hasContentDescription("List")).assertIsSelected()
        waitFor("2 episodes")
    }
}
