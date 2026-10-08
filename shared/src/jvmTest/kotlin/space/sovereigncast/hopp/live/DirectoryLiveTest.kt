package space.sovereigncast.hopp.live

import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.directory.AppleClient
import space.sovereigncast.hopp.directory.BuiltInKeys
import space.sovereigncast.hopp.directory.FyydClient
import space.sovereigncast.hopp.directory.PodcastIndexClient
import space.sovereigncast.hopp.directory.PodcastIndexCredentials
import space.sovereigncast.hopp.net.createHttpClient
import space.sovereigncast.hopp.platform.AppInfo
import kotlin.test.Test
import kotlin.test.assertTrue

/** The real directories, with the key this build carries. Runs only with
 *  HOPP_LIVE_DIRECTORY=1 — CI does not ask the Podcast Index on every push. */
class DirectoryLiveTest {
    private val enabled = System.getenv("HOPP_LIVE_DIRECTORY") == "1"
    private val http = createHttpClient(AppInfo("0.0.1", "JVM", "Test"))

    @Test
    fun theRealDirectoriesFindNodesignal() = runBlocking {
        if (!enabled) return@runBlocking
        val credentials = PodcastIndexCredentials(BuiltInKeys.PODCAST_INDEX_KEY, BuiltInKeys.PODCAST_INDEX_SECRET)
        val pi = PodcastIndexClient(http) { System.currentTimeMillis() / 1000 }
        val found = pi.search("nodesignal", valueOnly = true, credentials)
        println("podcast index: ${found.map { it.title }}")
        assertTrue(found.any { it.podcastGuid == "e8df0b13-47de-544a-99b7-ec7cbd960a16" })
        val byGuid = pi.byGuid("e8df0b13-47de-544a-99b7-ec7cbd960a16", credentials)
        println("by guid: ${byGuid?.title}, value ${byGuid?.hasValue}")
        assertTrue(byGuid?.hasValue == true)
        println("fyyd: ${FyydClient(http).search("bitcoin").take(3).map { it.title }}")
        println("apple: ${AppleClient(http).search("nodesignal").take(3).map { it.title }}")
    }
}
