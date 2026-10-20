package space.sovereigncast.hopp.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.data.SubscribeResult
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.net.FeedFetcher
import space.sovereigncast.hopp.nostr.Bech32
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.platform.MemorySecretStore
import space.sovereigncast.hopp.value.FakeRelay
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

private val feeds = mapOf(
    "https://pod.test/nodesignal.xml" to "/feeds/nodesignal-podhome.xml",
    "https://pod.test/selfhosted.xml" to "/feeds/sovereigncast-selfhosted.xml",
)

/** Subscriptions in step over Nostr (package 3 of docs/tasks/nostr-sync.md). */
class SubscriptionsSyncTest {
    private val relay = FakeRelay()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nsec = Bech32.encode("nsec", KeyPair.generate().secretKey)

    @AfterTest fun stop() = scope.cancel()

    /** A device with the listener's key: its own database, feeds from the test's web. */
    private inner class Device(var offline: Boolean = false) {
        var ms = 1_790_000_000_000L
        val db = HoppDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HoppDatabase.Schema.create(it) })
        val http = HttpClient(MockEngine { req ->
            val file = feeds[req.url.toString()]
            if (offline || file == null) respondError(HttpStatusCode.ServiceUnavailable)
            else respond(javaClass.getResourceAsStream(file)!!.readBytes())
        })
        val store = SyncStore(db) { ms }
        val journal = SyncJournal(store)
        val podcasts = PodcastRepository(db, FeedFetcher(http), { ms / 1000 }, journal = journal)
        val settings = SettingsRepository(db)
        val identity = IdentityRepository(MemorySecretStore(), RelayPublisher(relay), { ms / 1000 }).also { runBlocking { it.importKey(nsec) } }
        val engine = SyncEngine(store, SyncRelays(RelayPublisher(relay)) { ms / 1000 }, identity) { ms }
        val sync = SyncManager(engine, store, journal, settings, scope, { listOf(SubscriptionsDocument(db, podcasts)) }, { ms }, publishDelay = 1.hours)

        fun urls() = db.podcastQueries.selectAll().executeAsList().map { it.feed_url }.toSet()
        fun row(url: String) = db.podcastQueries.selectByFeedUrl(url).executeAsOne()
    }

    @Test
    fun aNewDeviceGetsTheLibraryAndAShowGivenUpGoesEverywhere() = runBlocking<Unit> {
        val phone = Device()
        phone.podcasts.subscribe("https://pod.test/nodesignal.xml")
        val s = phone.podcasts.subscribe("https://pod.test/selfhosted.xml") as SubscribeResult.Subscribed
        phone.podcasts.setSpeed(s.podcastId, 1.5f)
        phone.podcasts.setAutoDownload(s.podcastId, false)
        // Sync turned on later: what the phone had before goes out.
        phone.sync.enable()
        assertNull(phone.sync.status.value.problem)

        // A new tablet, nothing on it: turning sync on restores the library.
        val tablet = Device()
        tablet.sync.enable()
        assertEquals(phone.urls(), tablet.urls())
        val restored = tablet.row("https://pod.test/selfhosted.xml")
        assertEquals(1.5, restored.playback_speed)
        assertEquals(0L, restored.auto_download)

        // Given up on the tablet: gone from the phone too, and it stays gone.
        tablet.ms += 60_000
        tablet.podcasts.unsubscribe(restored.id)
        tablet.sync.syncNow()
        phone.ms += 120_000
        phone.sync.syncNow()
        assertEquals(setOf("https://pod.test/nodesignal.xml"), phone.urls())
        tablet.sync.syncNow()
        assertEquals(setOf("https://pod.test/nodesignal.xml"), tablet.urls())
    }

    @Test
    fun withSyncOffNothingIsNotedOrSent() = runBlocking<Unit> {
        val phone = Device()
        phone.podcasts.subscribe("https://pod.test/nodesignal.xml")
        phone.sync.syncNow()
        assertTrue(phone.store.entries(SyncDocs.SUBSCRIPTIONS).isEmpty())
        assertTrue(relay.let { r -> RelayPublisher(r).query(listOf(kotlinx.serialization.json.Json.parseToJsonElement("""{"kinds":[30078]}""") as kotlinx.serialization.json.JsonObject), listOf("wss://r")) }.isEmpty())
    }

    @Test
    fun aFeedOutOfReachIsTriedAgainOnTheNextRound() = runBlocking<Unit> {
        val phone = Device()
        phone.sync.enable()
        phone.ms += 1000
        phone.podcasts.subscribe("https://pod.test/nodesignal.xml")
        phone.sync.syncNow()
        val tablet = Device(offline = true)
        tablet.sync.enable()
        assertTrue(tablet.urls().isEmpty())
        tablet.offline = false
        tablet.sync.syncNow()
        assertEquals(setOf("https://pod.test/nodesignal.xml"), tablet.urls())
    }

    @Test
    fun turningSyncOffForgetsWhatThisDeviceKnew() = runBlocking<Unit> {
        val phone = Device()
        phone.podcasts.subscribe("https://pod.test/nodesignal.xml")
        phone.sync.enable()
        assertTrue(phone.store.entries(SyncDocs.SUBSCRIPTIONS).isNotEmpty())
        phone.sync.disable()
        assertTrue(phone.store.entries(SyncDocs.SUBSCRIPTIONS).isEmpty())
        assertEquals(false, phone.settings.settings.first().nostrSync)
        // The library itself stays.
        assertEquals(setOf("https://pod.test/nodesignal.xml"), phone.urls())
    }
}
