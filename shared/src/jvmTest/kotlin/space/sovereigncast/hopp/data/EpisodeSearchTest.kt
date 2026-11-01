package space.sovereigncast.hopp.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.net.FeedFetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import space.sovereigncast.hopp.testDatabase

/** Searching a show's episodes by topic, and favourites (device feedback 2026-10-11). */
class EpisodeSearchTest {
    private val feed = javaClass.getResource("/feeds/nodesignal-podhome.xml")!!.readBytes()
    private val http = HttpClient(MockEngine { respond(feed) })
    private val db = testDatabase()
    private val podcasts = PodcastRepository(db, FeedFetcher(http), { 1_000 })
    private val episodes = EpisodeRepository(db, http, { 1_000 })
    private fun test(block: suspend () -> Unit) = runBlocking { block() }
    private suspend fun subscribe() = (podcasts.subscribe("https://serve.podhome.fm/rss/x") as SubscribeResult.Subscribed).podcastId

    @Test
    fun aTopicFindsEpisodesByTitleAndDescription() = test {
        val id = subscribe()
        val hits = episodes.search(id, "meshtastic")
        assertTrue(hits.any { "E246" in it.title }, hits.map { it.title }.toString())
        assertTrue(hits.size < 325, "not everything")
        // Every word counts, and "ue" finds "ü".
        assertEquals(episodes.search(id, "Selbstsouveräne Kommunikation").map { it.id }, episodes.search(id, "selbstsouveraene kommunikation").map { it.id })
        assertTrue(episodes.search(id, "meshtastic xyzzy").isEmpty())
    }

    @Test
    fun matchingIgnoresTagsAndEntities() {
        assertTrue(episodeMatches("Folge 1", "<p>Wir sprechen &uuml;ber <b>Nodes</b></p>", searchWords("über nodes")))
        // What is inside a tag is no topic.
        kotlin.test.assertFalse(episodeMatches("Folge 1", "<p class=\"nodes\">Text</p>", searchWords("nodes")))
    }

    @Test
    fun favouritesAreKeptNewestFirst() = test {
        val id = subscribe()
        val (a, b) = episodes.ofPodcastPage(id, 2).first()
        episodes.setFavorite(a.id, true)
        var now = 1_000L
        val later = EpisodeRepository(db, http, { now })
        now = 2_000; later.setFavorite(b.id, true)
        assertEquals(listOf(b.id, a.id), episodes.favorites.first().map { it.id })
        assertTrue(episodes.item(a.id)!!.favorite)
        episodes.setFavorite(a.id, false)
        assertEquals(listOf(b.id), episodes.favorites.first().map { it.id })
    }
}
