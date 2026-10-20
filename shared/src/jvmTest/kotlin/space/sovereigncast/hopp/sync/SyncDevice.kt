package space.sovereigncast.hopp.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.data.EpisodeRepository
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.net.FeedFetcher
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.platform.MemorySecretStore
import space.sovereigncast.hopp.value.FakeRelay
import kotlin.time.Duration.Companion.hours

internal val testFeeds = mapOf(
    "https://pod.test/nodesignal.xml" to "/feeds/nodesignal-podhome.xml",
    "https://pod.test/selfhosted.xml" to "/feeds/sovereigncast-selfhosted.xml",
)

/** A device with the listener's key ([nsec]): its own database, feeds from the test's web, the shared [relay]. */
internal class SyncDevice(relay: FakeRelay, scope: CoroutineScope, nsec: String, var offline: Boolean = false) {
    var ms = 1_790_000_000_000L
    /** The episode playing here, as the player would say. */
    var playingId: Long? = null
    val adopted = mutableListOf<Triple<Long, Long, Boolean>>()
    val db = HoppDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HoppDatabase.Schema.create(it) })
    val http = HttpClient(MockEngine { req ->
        val file = testFeeds[req.url.toString()]
        if (offline || file == null) respondError(HttpStatusCode.ServiceUnavailable)
        else respond(javaClass.getResourceAsStream(file)!!.readBytes())
    })
    val store = SyncStore(db) { ms }
    val journal = SyncJournal(store)
    val podcasts = PodcastRepository(db, FeedFetcher(http), { ms / 1000 }, journal = journal)
    val episodes = EpisodeRepository(db, http, { ms / 1000 }, journal)
    val settings = SettingsRepository(db)
    val identity = IdentityRepository(MemorySecretStore(), RelayPublisher(relay), { ms / 1000 }).also { runBlocking { it.importKey(nsec) } }
    val engine = SyncEngine(store, SyncRelays(RelayPublisher(relay)) { ms / 1000 }, identity) { ms }
    val sync = SyncManager(engine, store, journal, settings, scope, {
        listOf(
            SubscriptionsDocument(db, podcasts),
            ProgressDocument(db, episodes, { ms / 1000 }, { it == playingId }) { id, pos, played -> adopted += Triple(id, pos, played) },
        )
    }, { ms }, publishDelay = 1.hours)

    fun urls() = db.podcastQueries.selectAll().executeAsList().map { it.feed_url }.toSet()
    fun row(url: String) = db.podcastQueries.selectByFeedUrl(url).executeAsOne()
    fun episode(url: String, guid: String) = db.episodeQueries.selectByGuid(row(url).id, guid).executeAsOne()
}
