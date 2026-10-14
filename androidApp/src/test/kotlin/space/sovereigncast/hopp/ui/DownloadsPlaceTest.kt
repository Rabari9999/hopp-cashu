package space.sovereigncast.hopp.ui

import android.content.pm.ProviderInfo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.mp.KoinPlatform
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import space.sovereigncast.hopp.EpisodesProvider
import space.sovereigncast.hopp.data.DownloadFiles
import space.sovereigncast.hopp.data.EpisodeRepository
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.data.SubscribeResult
import space.sovereigncast.hopp.db.HoppDatabase
import java.io.File

/**
 * Where the downloads are (device feedback 2026-10-14): in Hopp's private
 * storage, shown read only as "Hopp" in the Files app, and all of them
 * deleted from the settings at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "en-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadsPlaceTest {
    @get:Rule val rule = createComposeRule()
    private val koin get() = KoinPlatform.getKoin()

    /** Subscribes the test show and marks Ep 2 downloaded, with a file. */
    private fun downloaded(): File = runBlocking {
        val id = (koin.get<PodcastRepository>().subscribe("https://pod.test/feed.xml") as SubscribeResult.Subscribed).podcastId
        val ep = koin.get<EpisodeRepository>().ofPodcast(id).first().first { it.title == "Ep 2" }
        val dir = File(koin.get<DownloadFiles>().dir).apply { mkdirs() }
        val file = File(dir, "${ep.id}.mp3").apply { writeBytes(ByteArray(2048) { 7 }) }
        koin.get<HoppDatabase>().episodeQueries.setDownloaded(file.absolutePath, file.length(), ep.id)
        file
    }

    private fun provider(): EpisodesProvider {
        val info = ProviderInfo().apply { authority = "space.sovereigncast.hopp.episodes"; grantUriPermissions = true; exported = true; readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = "android.permission.MANAGE_DOCUMENTS" }
        return Robolectric.buildContentProvider(EpisodesProvider::class.java).create(info).get()
    }

    @Test
    fun theFilesAppShowsTheEpisodesByNameReadOnly() {
        rule.setContent { App() }
        val file = downloaded()
        val p = provider()
        p.queryChildDocuments(EpisodesProvider.ROOT, null, null as String?).use { c ->
            assertEquals(1, c.count); c.moveToFirst()
            assertEquals("Test Show – Ep 2.mp3", c.getString(c.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)))
            assertEquals(file.name, c.getString(c.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)))
        }
        p.openDocument(file.name, "r", null).use { assertEquals(2048L, it.statSize) }
        val refused = runCatching { p.openDocument(file.name, "w", null) }
        check(refused.isFailure) { "a file manager could write Hopp's episodes" }
    }

    @Test
    fun allDownloadsAreDeletedFromTheSettings() {
        rule.setContent { App() }
        val file = downloaded()
        rule.onNode(hasContentDescription("Settings")).performClick()
        rule.onNode(hasText("Downloads", substring = true) and hasClickAction()).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("in 1 episodes", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("Delete all downloads") and hasClickAction()).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Delete all 1 downloaded episodes", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText("Delete all downloads") and hasClickAction()).onLast().performClick()
        rule.waitUntil(10_000) { !file.exists() && rule.onAllNodesWithText("in 0 episodes", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(file.exists())
    }

    @Test
    fun namesAreSafeForFileSystems() {
        assertEquals("Show – A B C.mp3", EpisodesProvider.displayName("Show", "A/B:C", "mp3"))
        assertEquals("Episode.m4a", EpisodesProvider.displayName("", " ", "m4a"))
    }
}
