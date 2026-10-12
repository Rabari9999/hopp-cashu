package space.sovereigncast.hopp.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.core.app.ActivityScenario
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import space.sovereigncast.hopp.MainActivity

/**
 * "Open in Hopp" (docs/tasks/open-in-hopp-app-link.md, ADR 0002 §4): the App
 * Link and the hopp:// scheme, delivered to MainActivity as a phone would —
 * on a cold start and to the running app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "en-w411dp-h891dp")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class OpenInHoppTest {
    @get:Rule val rule = createEmptyComposeRule()
    private var activity: ActivityScenario<MainActivity>? = null

    @After fun close() { activity?.close() }

    private val feed = "https://pod.test/feed.xml"
    private fun appLink(feed: String = this.feed) = "https://www.sovereigncast.space/open?feed=" + Uri.encode(feed)
    private fun scheme(feed: String = this.feed) = "hopp://subscribe?feed=" + Uri.encode(feed)
    private fun view(link: String) = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(androidx.test.core.app.ApplicationProvider.getApplicationContext(), MainActivity::class.java)

    private fun start(intent: Intent = Intent(Intent.ACTION_MAIN).setClass(androidx.test.core.app.ApplicationProvider.getApplicationContext(), MainActivity::class.java)) {
        activity = ActivityScenario.launch(intent)
    }
    private fun deliver(link: String) { activity!!.onActivity { it.onNewIntent(view(link)) }; rule.waitForIdle() }

    private fun has(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(text: String) = try { rule.waitUntil(10_000) { has(text) } } catch (e: Throwable) {
        error("no \"$text\" on ${rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().flatMap { root -> texts(root) }}")
    }
    private fun texts(n: androidx.compose.ui.semantics.SemanticsNode): List<String> =
        n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }.orEmpty().map { it.text } + n.children.flatMap(::texts)
    private fun tap(text: String) = rule.onNode(hasText(text) and hasClickAction()).performClick()

    @Test
    fun theAppLinkOpensTheShowsPreviewOnAColdStart() {
        start(view(appLink()))
        waitFor("Subscribe")
        waitFor("Test Show")
        check(!has("✓ Subscribed"))
    }

    @Test
    fun theSchemeOpensItInTheRunningApp() {
        start()
        waitFor("Home")
        deliver(scheme())
        waitFor("Subscribe")
        waitFor("Test Show")
    }

    /** Subscribed: the show's page, not the preview — here after subscribing from the first link. */
    @Test
    fun aSubscribedShowOpensItsPage() {
        start(view(appLink()))
        waitFor("Subscribe")
        tap("Subscribe"); waitFor("✓ Subscribed")
        tap("Home"); waitFor("Ep 2")
        deliver(appLink())
        waitFor("✓ Subscribed")
        check(!has("Subscribe")) { "the preview opened, not the show's page" }
        // Library owns a subscribed show's page.
        tap("Library"); waitFor("Test Show")
    }

    /** Without a usable feed Hopp just opens — on a cold start and when running. */
    @Test
    fun aLinkWithoutAFeedOpensHoppAsItIs() {
        start(view("https://www.sovereigncast.space/open"))
        waitFor("Home")
        listOf(
            "https://www.sovereigncast.space/open?feed=",
            "https://www.sovereigncast.space/open?feed=" + Uri.encode("javascript:alert(1)"),
            "hopp://subscribe?feed=" + Uri.encode("file:///sdcard/feed.xml"),
        ).forEach { deliver(it) }
        rule.waitForIdle()
        check(!has("Subscribe") && !has("Test Show") && has("Home"))
    }
}
