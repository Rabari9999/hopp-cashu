package space.sovereigncast.hopp.ui

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import space.sovereigncast.hopp.ui.library.HomeSections

/** Home's sections arranged and hidden by the listener (device feedback 2026-10-13). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "en-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeLayoutTest {
    @get:Rule val rule = createComposeRule()

    private fun has(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(text: String) = rule.waitUntil(10_000) { has(text) }
    private fun tap(text: String) = rule.onNode(hasText(text) and hasClickAction()).performClick()
    private val settings get() = org.koin.mp.KoinPlatform.getKoin().get<space.sovereigncast.hopp.data.SettingsRepository>()
    private fun screen(): List<String> = rule.onAllNodes(isRoot()).fetchSemanticsNodes().flatMap { root ->
        fun walk(n: SemanticsNode): List<String> =
            n.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().map { it.text } + n.children.flatMap(::walk)
        walk(root)
    }

    /** Subscribe to the test show: Ep 2 is new, Ep 1 the back catalogue under "From your shows". */
    private fun home() {
        rule.setContent { App() }
        tap("Discover")
        rule.onNode(hasSetTextAction()).performTextInput("https://pod.test/feed.xml")
        tap("Open as feed"); waitFor("Subscribe")
        tap("Subscribe"); waitFor("✓ Subscribed")
        tap("Home"); waitFor("From your shows")
    }

    @Test
    fun theListenersOrderIsHomesOrder() {
        runBlocking { settings.update { it.copy(homeOrder = listOf(HomeSections.FROM_SHOWS, HomeSections.NEW)) } }
        home(); waitFor("New")
        val s = screen()
        check(s.indexOf("From your shows") < s.indexOf("New")) { "not in the listener's order: $s" }
    }

    /** From Home to its settings, a section switched off, and back: it is gone. */
    @Test
    fun aSectionSwitchedOffLeavesHome() {
        home()
        // Home itself, not the row of covers in it.
        rule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Customize Home"))
        tap("Customize Home"); waitFor("From your shows")
        rule.onNode(isToggleable() and hasContentDescription("From your shows")).performClick()
        rule.waitUntil(10_000) { runBlocking { HomeSections.FROM_SHOWS in settings.settings.first().homeHidden } }
        rule.onNode(hasContentDescription("Back")).performClick()
        waitFor("New")
        check(!has("From your shows")) { "the hidden section is still on Home: ${screen()}" }
        check(has("Ep 2")) { "New went too" }
    }
}
