package space.sovereigncast.hopp.ui

import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import space.sovereigncast.hopp.MainActivity

/**
 * Another podcast app's OPML export, shared to Hopp or opened with it
 * (maintainer, 2026-10-21): the subscriptions page asks, then imports.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "en-w411dp-h891dp")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class OpmlFromAnotherAppTest {
    @get:Rule val rule = createEmptyComposeRule()
    private var activity: ActivityScenario<MainActivity>? = null
    @After fun close() { activity?.close() }

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val opml = """<?xml version="1.0"?><opml version="2.0"><body>
        <outline type="rss" text="Test Show" xmlUrl="https://pod.test/feed.xml"/>
        <outline type="rss" text="Alpha Show" xmlUrl="https://pod.test/alpha.xml"/></body></opml>"""

    private fun has(text: String, substring: Boolean = false) = rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(text: String, substring: Boolean = false) = rule.waitUntil(15_000) { has(text, substring) }
    private fun tap(text: String) = rule.onNode(hasText(text) and hasClickAction()).apply { runCatching { performScrollTo() } }.performClick()

    @Test
    fun aSharedExportIsImportedAfterAsking() {
        activity = ActivityScenario.launch(Intent(Intent.ACTION_SEND).setType("text/x-opml").putExtra(Intent.EXTRA_TEXT, opml).setClass(app, MainActivity::class.java))
        waitFor("The file names 2 shows. Subscribe to them in Hopp?")
        tap("Subscribe")
        waitFor("2 subscribed, 0 already there, 0 failed")
    }

    /** "Open with Hopp" on a file: read through its content address; the shows already here are counted. */
    @Test
    fun anOpenedFileCountsWhatIsHereAlready() {
        val file = java.io.File(app.cacheDir, "export.opml").apply { writeText(opml) }
        val uri = android.net.Uri.parse("content://test.opml/export.opml")
        org.robolectric.Robolectric.setupContentProvider(FileProvider::class.java, "test.opml")
        FileProvider.file = file
        activity = ActivityScenario.launch(Intent(Intent.ACTION_MAIN).setClass(app, MainActivity::class.java))
        // One of the two subscribed first.
        kotlinx.coroutines.runBlocking { org.koin.mp.KoinPlatform.getKoin().get<space.sovereigncast.hopp.data.PodcastRepository>().subscribe("https://pod.test/feed.xml") }
        activity!!.onActivity { it.onNewIntent(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/x-opml")) }
        waitFor("The file names 2 shows, 1 of them you have already. Subscribe to the others?")
    }

    /** Any XML can be shared to Hopp: one without shows is said so, nothing imported. */
    @Test
    fun aFileWithoutShowsIsSaidSo() {
        activity = ActivityScenario.launch(Intent(Intent.ACTION_SEND).setType("text/xml").putExtra(Intent.EXTRA_TEXT, "<rss><channel/></rss>").setClass(app, MainActivity::class.java))
        waitFor("There are no shows in this file — is it an OPML export?")
        check(!has("Subscribe")) { "nothing to subscribe to" }
    }

    /** What another app hands over as a content address. */
    class FileProvider : android.content.ContentProvider() {
        companion object { var file: java.io.File? = null }
        override fun onCreate() = true
        override fun openFile(uri: android.net.Uri, mode: String) = android.os.ParcelFileDescriptor.open(file!!, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        override fun query(uri: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
        override fun getType(uri: android.net.Uri) = "text/x-opml"
        override fun insert(uri: android.net.Uri, values: android.content.ContentValues?) = null
        override fun delete(uri: android.net.Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: android.net.Uri, values: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0
    }
}
