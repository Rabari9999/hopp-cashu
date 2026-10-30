package space.sovereigncast.hopp.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.nostr.Bech32
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.value.FakeRelay
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Progress and favourites in step over Nostr (package 4 of docs/tasks/nostr-sync.md). */
class ProgressSyncTest {
    private val relay = FakeRelay()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nsec = Bech32.encode("nsec", KeyPair.generate().secretKey)
    private val feed = "https://pod.test/nodesignal.xml"

    @AfterTest fun stop() = scope.cancel()

    /** Phone and tablet, both subscribed to Nodesignal, sync on. */
    private fun pair(): Pair<SyncDevice, SyncDevice> = runBlocking {
        val phone = SyncDevice(relay, scope, nsec)
        phone.podcasts.subscribe(feed)
        phone.sync.enable()
        val tablet = SyncDevice(relay, scope, nsec)
        tablet.ms += 1_000
        tablet.sync.enable()
        phone to tablet
    }

    private suspend fun guids(d: SyncDevice) = d.db.episodeQueries.selectByPodcast(d.row(feed).id).awaitAsList().map { it.guid }

    @Test
    fun whereTheListenerStoppedFollowsToTheOtherDevice() = runBlocking<Unit> {
        val (phone, tablet) = pair()
        val (a, b) = guids(phone).take(2)
        phone.ms += 60_000
        phone.episodes.savePosition(phone.episode(feed, a).id, 754_000, paused = true)
        phone.episodes.setPlayed(phone.episode(feed, b).id, true)
        phone.episodes.setFavorite(phone.episode(feed, b).id, true)
        phone.sync.syncNow()

        tablet.ms += 120_000
        tablet.sync.syncNow()
        assertEquals(754_000, tablet.episode(feed, a).position_ms)
        assertEquals(phone.episode(feed, a).last_played_at, tablet.episode(feed, a).last_played_at)
        assertEquals(1L, tablet.episode(feed, b).played)
        assertNotNull(tablet.episode(feed, b).favorite_at)
        // The paused player on the tablet stands there too.
        assertTrue(Triple(tablet.episode(feed, a).id, 754_000L, false) in tablet.adopted)

        // Listening on: the tablet's later position goes back; the favourite taken back too.
        tablet.ms += 60_000
        tablet.episodes.savePosition(tablet.episode(feed, a).id, 900_000, paused = true)
        tablet.episodes.setFavorite(tablet.episode(feed, b).id, false)
        tablet.sync.syncNow()
        phone.ms += 300_000
        phone.sync.syncNow()
        assertEquals(900_000, phone.episode(feed, a).position_ms)
        assertNull(phone.episode(feed, b).favorite_at)
    }

    /**
     * Maintainer 2026-10-30: "this and older" on a show of hundreds of episodes made one entry each —
     * a progress document several events long, sent again with every change. Now one entry says it;
     * the other device marks the same episodes, the one started there too, and one taken back
     * afterwards stays taken back.
     */
    @Test
    fun thisAndOlderHeardGoesAsOneEntry() = runBlocking<Unit> {
        val (phone, tablet) = pair()
        val list = phone.episodes.ofPodcast(phone.row(feed).id).first().sortedByDescending { it.publishedAt }
        assertTrue(list.size > 300, "the test feed has ${list.size} episodes")
        val started = list[5]
        phone.ms += 60_000
        phone.episodes.savePosition(started.id, 120_000, paused = true)
        phone.sync.syncNow()
        tablet.ms += 1_000
        tablet.sync.syncNow()
        assertEquals(120_000, tablet.episode(feed, started.guid).position_ms)

        phone.ms += 60_000
        phone.episodes.setPlayedUpTo(list[1])
        assertEquals(null, phone.sync.syncNow().problem)
        val sent = phone.store.entries(SyncDocs.PROGRESS)
        assertTrue(sent.size <= 3, "one entry for all, one for the episode started: ${sent.keys}")
        assertTrue(sent.keys.any { it.startsWith("upto\n") })

        tablet.ms += 120_000
        tablet.sync.syncNow()
        val heard = tablet.db.episodeQueries.selectByPodcast(tablet.row(feed).id).awaitAsList().filter { it.played == 1L }.map { it.guid }.toSet()
        assertEquals(list.drop(1).map { it.guid }.toSet(), heard)
        assertEquals(0L, tablet.episode(feed, list[0].guid).played)
        assertEquals(0L, tablet.episode(feed, started.guid).position_ms)

        // Taken back on the tablet afterwards: unheard on the phone too, not heard again by the entry for all.
        tablet.ms += 60_000
        tablet.episodes.setPlayed(tablet.episode(feed, list[3].guid).id, false)
        tablet.sync.syncNow()
        phone.ms += 300_000
        phone.sync.syncNow()
        assertEquals(0L, phone.episode(feed, list[3].guid).played)
        assertEquals(1L, phone.episode(feed, list[4].guid).played)

        // A third device, new, takes it all from the one entry.
        val laptop = SyncDevice(relay, scope, nsec)
        laptop.ms = phone.ms + 1_000
        laptop.podcasts.subscribe(feed)
        laptop.sync.enable()
        assertEquals(1L, laptop.episode(feed, list[4].guid).played)
        assertEquals(0L, laptop.episode(feed, list[3].guid).played)
        assertEquals(0L, laptop.episode(feed, list[0].guid).played)
    }

    /** All heard before sync was on: it goes out as the one entry, not as one per episode. */
    @Test
    fun allHeardBeforeSyncGoesOutAsOneEntry() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.podcasts.subscribe(feed)
        phone.episodes.setShowPlayed(phone.row(feed).id)
        phone.sync.enable()
        assertTrue(phone.store.entries(SyncDocs.PROGRESS).size <= 2, "${phone.store.entries(SyncDocs.PROGRESS).keys}")
        val tablet = SyncDevice(relay, scope, nsec)
        tablet.ms += 1_000
        tablet.podcasts.subscribe(feed)
        tablet.sync.enable()
        assertTrue(tablet.db.episodeQueries.selectByPodcast(tablet.row(feed).id).awaitAsList().all { it.played == 1L })
    }

    @Test
    fun theEpisodePlayingHereIsNotMovedUntilItPauses() = runBlocking<Unit> {
        val (phone, tablet) = pair()
        val a = guids(phone).first()
        phone.ms += 60_000
        phone.episodes.savePosition(phone.episode(feed, a).id, 300_000, paused = true)
        phone.sync.syncNow()
        tablet.playingId = tablet.episode(feed, a).id
        tablet.ms += 1_000
        tablet.sync.syncNow()
        assertEquals(0, tablet.episode(feed, a).position_ms)
        tablet.playingId = null
        tablet.sync.syncNow()
        assertEquals(300_000, tablet.episode(feed, a).position_ms)
    }

    @Test
    fun aFavouriteAndAPositionOfOneEpisodeDoNotOverwriteEachOther() = runBlocking<Unit> {
        val (phone, tablet) = pair()
        val a = guids(phone).first()
        phone.ms += 60_000
        phone.episodes.setFavorite(phone.episode(feed, a).id, true)
        tablet.ms += 120_000
        tablet.episodes.savePosition(tablet.episode(feed, a).id, 42_000, paused = true)
        phone.sync.syncNow(); tablet.sync.syncNow(); phone.sync.syncNow()
        for (d in listOf(phone, tablet)) {
            assertNotNull(d.episode(feed, a).favorite_at)
            assertEquals(42_000, d.episode(feed, a).position_ms)
        }
    }

    @Test
    fun progressFromBeforeSyncGoesOutWhenItIsTurnedOn() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.podcasts.subscribe(feed)
        val a = guids(phone).first()
        phone.episodes.savePosition(phone.episode(feed, a).id, 123_000)
        phone.episodes.setFavorite(phone.episode(feed, a).id, true)
        phone.sync.enable()
        val tablet = SyncDevice(relay, scope, nsec)
        tablet.ms += 1_000
        tablet.sync.enable()
        assertEquals(123_000, tablet.episode(feed, a).position_ms)
        assertNotNull(tablet.episode(feed, a).favorite_at)
    }

    @Test
    fun positionsOlderThan90DaysAreDroppedFavouritesStay() {
        val doc = ProgressDocument(SyncDevice(relay, scope, nsec).db, SyncDevice(relay, scope, nsec).episodes, { 0 })
        val now = 200L * 24 * 3600 * 1000
        val old = SyncEntry(now - 91L * 24 * 3600 * 1000)
        assertTrue(doc.expired(SyncDocs.progressKey("guid:x", "e"), old, now))
        assertEquals(false, doc.expired(SyncDocs.favoriteKey("guid:x", "e"), old, now))
        assertEquals(false, doc.expired(SyncDocs.progressKey("guid:x", "e"), SyncEntry(now - 1000), now))
    }
}
