package space.sovereigncast.hopp.ui

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
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

    /** Subscribe to the test show: Ep 2 is new; Ep 1, the back catalogue, stays off Home. */
    private fun home() {
        rule.setContent { App() }
        tap("Discover")
        rule.onNode(hasSetTextAction()).performTextInput("https://pod.test/feed.xml")
        tap("Open as feed"); waitFor("Subscribe")
        tap("Subscribe"); waitFor("✓ Subscribed")
        tap("Home"); waitFor("Your shows"); waitFor("New")
    }

    /** Hopp's own order: the shows before what is new (device feedback 2026-10-13). */
    @Test
    fun theShowsComeBeforeWhatIsNew() {
        home()
        val s = screen()
        check(s.indexOf("Your shows") < s.indexOf("New")) { "not in Hopp's order: $s" }
        check("From your shows" !in s && "Ep 1" !in s) { "the back catalogue is on Home: $s" }
    }

    @Test
    fun theListenersOrderIsHomesOrder() {
        runBlocking { settings.update { it.copy(homeOrder = listOf(HomeSections.NEW, HomeSections.YOUR_SHOWS)) } }
        home()
        val s = screen()
        check(s.indexOf("New") < s.indexOf("Your shows")) { "not in the listener's order: $s" }
    }

    /** From Home to its settings, a section switched off, and back: it is gone. */
    @Test
    fun aSectionSwitchedOffLeavesHome() {
        home()
        rule.onNode(hasContentDescription("Customize Home") and hasClickAction()).performClick(); waitFor("Your shows")
        rule.onNode(isToggleable() and hasContentDescription("Your shows")).performClick()
        rule.waitUntil(10_000) { runBlocking { HomeSections.YOUR_SHOWS in settings.settings.first().homeHidden } }
        rule.onNode(hasContentDescription("Back")).performClick()
        waitFor("New")
        check(!has("Your shows")) { "the hidden section is still on Home: ${screen()}" }
        check(has("Ep 2")) { "New went too" }
    }

    /** "Customize Home" at the top, beside the greeting, not below everything (device feedback 2026-10-17). */
    @Test
    fun customizeIsAtTheTop() {
        home()
        rule.onNode(hasContentDescription("Customize Home") and hasClickAction()).assertIsDisplayed().performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(isToggleable() and hasContentDescription("Your shows")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasContentDescription("Back")).performClick()
        waitFor("New")
        check(!has("Customize Home")) { "the button below is still there" }
    }

    /** The name the listener boosts with greets them on Home (device feedback 2026-10-13). */
    @Test
    fun homeGreetsByTheBoostName() {
        runBlocking { settings.update { it.copy(senderName = "Satoshi") } }
        home()
        rule.waitUntil(10_000) { screen().any { "Satoshi" in it } }
    }

    /** Device feedback 2026-10-17: a long greeting shrinks to one line instead of running off. */
    @Test @Config(qualifiers = "en-w360dp-h800dp")
    fun aLongGreetingFitsOneLine() {
        runBlocking { settings.update { it.copy(senderName = "Thorsten") } }
        home()
        rule.waitUntil(10_000) { screen().any { "Thorsten" in it } }
        val node = rule.onAllNodes(androidx.compose.ui.test.hasText("Thorsten", substring = true)).fetchSemanticsNodes().first()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val l = layouts.first()
        check(l.lineCount == 1 && !l.isLineEllipsized(0) && l.getLineRight(0) <= l.size.width) { "the greeting is cut off" }
        // The greeting goes with the hour: "Hello Thorsten" fits as it is, "Good morning, Thorsten" does not.
        check(l.layoutInput.text.length <= 16 || l.layoutInput.style.fontSize.value < 24f) { "not made smaller: ${l.layoutInput.text}" }
    }

    /**
     * Device feedback 2026-10-16: "Your shows" opened scrolled by one. The show
     * with a Release Boosting round moves to the front once its instance answers,
     * after the strip is shown, and the strip kept the old first show in view.
     */
    @Test
    fun yourShowsStartsAtTheFirstShow() {
        // A cold start: the shows are in the database, the instance answers later.
        val podcasts = org.koin.mp.KoinPlatform.getKoin().get<space.sovereigncast.hopp.data.PodcastRepository>()
        kotlinx.coroutines.runBlocking {
            podcasts.subscribe("https://pod.test/alpha.xml")
            podcasts.subscribe("https://pod.test/sc.xml")
        }
        rule.setContent { App() }
        waitFor("Your shows")
        // The tile in the strip — the rows under "New" start at the edge (0).
        fun left(t: String) = rule.onAllNodes(hasText(t) and hasClickAction()).fetchSemanticsNodes().map { it.boundsInRoot.left }.filter { it > 0f }.minOrNull()
        // First alphabetical: Alpha, then Nodesignal.
        rule.waitUntil(10_000) { rule.waitForIdle(); (left("Alpha Show") ?: 999f) < (left("Nodesignal") ?: 0f) }
        // The instance has answered (its round is listed to take part in): Nodesignal moves first.
        rule.waitUntil(30_000) { rule.waitForIdle(); has("Take part") }
        rule.waitForIdle()
        check((left("Nodesignal") ?: 999f) < 100f) { "the strip does not start at its first show — Nodesignal at ${left("Nodesignal")}" }
        val strip = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange)).fetchSemanticsNodes()
        check(strip.all { it.config[androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange].value() == 0f }) { "a strip does not start at its first show" }
    }
}
