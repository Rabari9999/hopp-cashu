package space.sovereigncast.hopp.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.data.SubscribeResult
import space.sovereigncast.hopp.nostr.Bech32
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.value.FakeRelay
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Subscriptions in step over Nostr (package 3 of docs/tasks/nostr-sync.md). */
class SubscriptionsSyncTest {
    private val relay = FakeRelay()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nsec = Bech32.encode("nsec", KeyPair.generate().secretKey)

    @AfterTest fun stop() = scope.cancel()

    private fun Device(offline: Boolean = false) = SyncDevice(relay, scope, nsec, offline)

    @Test
    fun aNewDeviceGetsTheLibraryAndAShowGivenUpGoesEverywhere() = runBlocking<Unit> {
        val phone = Device()
        phone.podcasts.subscribe("https://pod.test/nodesignal.xml")
        val s = phone.podcasts.subscribe("https://pod.test/selfhosted.xml") as SubscribeResult.Subscribed
        phone.podcasts.setSpeed(s.podcastId, 1.5f)
        phone.podcasts.setAutoDownload(s.podcastId, false)
        phone.podcasts.setSkip(s.podcastId, 30, 15)
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
        assertEquals(30L to 15L, restored.skip_intro_seconds to restored.skip_outro_seconds)

        // Changed on the tablet: the phone follows; an odd value from elsewhere counts as none.
        tablet.ms += 30_000
        tablet.podcasts.setSkip(restored.id, 45, 0)
        tablet.sync.syncNow()
        phone.ms += 60_000
        phone.sync.syncNow()
        phone.row("https://pod.test/selfhosted.xml").let { assertEquals(45L to 0L, it.skip_intro_seconds to it.skip_outro_seconds) }

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

    /** ADR 0024, 4a: a show left out of what is shared with mutual Nostr contacts is left out on every device. */
    @Test
    fun aShowLeftOutOfSharingIsLeftOutEverywhere() = runBlocking<Unit> {
        val phone = Device()
        phone.podcasts.subscribe("https://pod.test/nodesignal.xml")
        val s = phone.podcasts.subscribe("https://pod.test/selfhosted.xml") as SubscribeResult.Subscribed
        phone.podcasts.setContactsHidden(s.podcastId, true)
        assertEquals(listOf("https://pod.test/nodesignal.xml"), phone.podcasts.shared().map { it.feed_url })
        phone.sync.enable()
        val tablet = Device()
        tablet.sync.enable()
        assertEquals(1L, tablet.row("https://pod.test/selfhosted.xml").contacts_hidden)
        assertEquals(0L, tablet.row("https://pod.test/nodesignal.xml").contacts_hidden)
        // Shared again on the tablet: so on the phone.
        tablet.ms += 30_000
        tablet.podcasts.setContactsHidden(tablet.row("https://pod.test/selfhosted.xml").id, false)
        tablet.sync.syncNow()
        phone.ms += 60_000
        phone.sync.syncNow()
        assertEquals(0L, phone.row("https://pod.test/selfhosted.xml").contacts_hidden)
    }

    /**
     * Maintainer 2026-11-03: tags on shows, as in AntennaPod — any number, on every device; renamed and taken off
     * there too. An entry from a Hopp before tags (none named at all) leaves a device's tags alone.
     */
    @Test
    fun tagsFollowToEveryDeviceAndAnOlderHoppLeavesThem() = runBlocking<Unit> {
        val phone = Device()
        val a = (phone.podcasts.subscribe("https://pod.test/nodesignal.xml") as SubscribeResult.Subscribed).podcastId
        val b = (phone.podcasts.subscribe("https://pod.test/selfhosted.xml") as SubscribeResult.Subscribed).podcastId
        phone.podcasts.setTags(a, listOf("Bitcoin", " politik ", "bitcoin"))
        phone.podcasts.setTagFor("Bitcoin", setOf(a, b))
        assertEquals(listOf("Bitcoin", "politik"), phone.podcasts.show(a).first()!!.tags, "trimmed, once whatever the case, A–Z")
        phone.sync.enable()
        val tablet = Device()
        tablet.sync.enable()
        assertEquals("Bitcoin\npolitik", tablet.row("https://pod.test/nodesignal.xml").tags)
        assertEquals("Bitcoin", tablet.row("https://pod.test/selfhosted.xml").tags)

        tablet.ms += 30_000
        tablet.podcasts.renameTag("politik", "Politik")
        tablet.podcasts.renameTag("Bitcoin", null)
        tablet.sync.syncNow()
        phone.ms += 60_000
        phone.sync.syncNow()
        assertEquals(listOf("Politik"), phone.podcasts.show(a).first()!!.tags)
        assertEquals(emptyList(), phone.podcasts.show(b).first()!!.tags)

        // A Hopp before tags changes the show's speed: its entry names no tags, and they stay.
        val show = SyncDocs.showKey(phone.row("https://pod.test/nodesignal.xml").podcast_guid, "https://pod.test/nodesignal.xml")
        tablet.ms += 30_000
        tablet.journal.record(SyncDocs.SUBSCRIPTIONS, show, kotlinx.serialization.json.buildJsonObject {
            put("url", kotlinx.serialization.json.JsonPrimitive("https://pod.test/nodesignal.xml")); put("speed", kotlinx.serialization.json.JsonPrimitive(1.5f)); put("autoDl", kotlinx.serialization.json.JsonPrimitive(true))
        })
        tablet.sync.syncNow()
        phone.ms += 60_000
        phone.sync.syncNow()
        assertEquals(1.5f, phone.podcasts.show(a).first()!!.playbackSpeed)
        assertEquals(listOf("Politik"), phone.podcasts.show(a).first()!!.tags)
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

    /** Delta audit 2 2026-10-22, HN-07 (B07): given up on the tablet, changed on the phone right while that
     *  arrives — the show stays, and with it its progress and its place in "Up next". */
    @Test
    fun aShowChangedWhileItsRemovalArrivesKeepsItsEpisodes() = runBlocking<Unit> {
        val feed = "https://pod.test/selfhosted.xml"
        val tablet = Device()
        tablet.podcasts.subscribe(feed)
        tablet.sync.enable()
        val phone = Device()
        phone.sync.enable()
        val id = phone.row(feed).id
        val episode = phone.db.episodeQueries.selectByPodcast(id).awaitAsList().first()
        phone.episodes.savePosition(episode.id, 123_456, paused = true)
        phone.queue.playLast(episode.id)
        phone.sync.syncNow()

        tablet.ms += 60_000
        tablet.podcasts.unsubscribe(tablet.row(feed).id)
        tablet.sync.syncNow()

        phone.ms += 120_000
        val real = SubscriptionsDocument(phone.db, phone.podcasts)
        val racing = object : SyncedDocument by real {
            override suspend fun apply(entries: Map<String, SyncEntry>, stillWins: suspend (String, SyncEntry) -> Boolean): Set<String> {
                if (entries.values.any { it.del }) phone.podcasts.setSpeed(id, 1.5f)
                return real.apply(entries, stillWins)
            }
        }
        phone.engine.sync(racing)
        assertEquals(setOf(feed), phone.urls())
        assertEquals(123_456L, phone.episode(feed, episode.guid).position_ms)
        assertEquals(listOf(episode.guid), phone.queueGuids())
        assertEquals(1.5, phone.row(feed).playback_speed)
    }
}
