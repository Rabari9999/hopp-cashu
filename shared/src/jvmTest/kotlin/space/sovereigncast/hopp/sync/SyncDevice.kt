package space.sovereigncast.hopp.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.data.EpisodeRepository
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.data.QueueRepository
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.net.FeedFetcher
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.platform.MemorySecretStore
import space.sovereigncast.hopp.testDatabase
import space.sovereigncast.hopp.value.FakeRelay
import kotlin.time.Duration.Companion.hours

internal val testFeeds = mapOf(
    "https://pod.test/nodesignal.xml" to "/feeds/nodesignal-podhome.xml",
    "https://pod.test/selfhosted.xml" to "/feeds/sovereigncast-selfhosted.xml",
)

/** A device with the listener's key ([nsec]): its own database, feeds from the test's web, the shared [relay]. */
/** [db] and [secrets] from another: the same device started again. */
internal class SyncDevice(
    relay: space.sovereigncast.hopp.nostr.RelayConnector, scope: CoroutineScope, nsec: String, var offline: Boolean = false,
    val db: HoppDatabase = testDatabase(),
    val secrets: MemorySecretStore = MemorySecretStore(),
    publishDelay: kotlin.time.Duration = 1.hours,
) {
    var ms = 1_790_000_000_000L
    /** The episode playing here, as the player would say. */
    var playingId: Long? = null
    val adopted = mutableListOf<Triple<Long, Long, Boolean>>()
    /** What plays now elsewhere, as the player would have been told. */
    val nowAdopted = mutableListOf<Long>()
    /** How often automatic downloads were turned off here by sync — the waiting ones dropped. */
    var autoDownloadsDropped = 0
    /** Settings this device does not sync yet — as a build from before they were added. */
    var notSyncedYet: Set<String> = emptySet()
    val http = HttpClient(MockEngine { req ->
        val file = testFeeds[req.url.toString()]
        if (offline || file == null) respondError(HttpStatusCode.ServiceUnavailable)
        else respond(javaClass.getResourceAsStream(file)!!.readBytes())
    })
    val store = SyncStore(db) { ms }
    val journal = SyncJournal(store)
    val settings = SettingsRepository(db, journal)
    val podcasts = PodcastRepository(db, FeedFetcher(http), { ms / 1000 }, journal = journal, autoDownloads = { settings.current().autoDownloads })
    val episodes = EpisodeRepository(db, http, { ms / 1000 }, journal)
    val queue = QueueRepository(db, journal)
    val listening = space.sovereigncast.hopp.data.ListeningRepository(db, secrets, { ms / 1000 }, journal) { kotlinx.datetime.TimeZone.UTC }
    val identity = IdentityRepository(secrets, RelayPublisher(relay), { ms / 1000 }, hoppSyncRelays = emptyList()).also { runBlocking { if (it.identity.value == null) it.importKey(nsec) } }
    val keys = SyncKeys(identity, RelayPublisher(relay), secrets) { ms }
    val engine = SyncEngine(store, SyncRelays(RelayPublisher(relay)) { ms / 1000 }, identity, keys) { ms }
    val sync = SyncManager(engine, store, journal, settings, scope, {
        listOf(
            SettingsDocument(settings) { autoDownloadsDropped++ }.let { doc ->
                object : SyncedDocument by doc { override suspend fun snapshot() = doc.snapshot().filterKeys { it !in notSyncedYet } }
            },
            SubscriptionsDocument(db, podcasts),
            ProgressDocument(db, episodes, { ms / 1000 }, { it == playingId }) { id, pos, played -> adopted += Triple(id, pos, played) },
            QueueDocument(db, queue) { id, _ -> nowAdopted += id },
            StatsDocument(db, listening) { ms },
        )
    }, { ms }, publishDelay = publishDelay)

    suspend fun urls() = db.podcastQueries.selectAll().awaitAsList().map { it.feed_url }.toSet()
    suspend fun row(url: String) = db.podcastQueries.selectByFeedUrl(url).awaitAsOne()
    suspend fun episode(url: String, guid: String) = db.episodeQueries.selectByGuid(row(url).id, guid).awaitAsOne()
    /** "Up next" as episode guids, in order. */
    suspend fun queueGuids() = db.queueQueries.selectItems().awaitAsList().map { it.guid }
}
