package space.sovereigncast.hopp.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.AsyncOnlyDriver
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.nostr.Bech32
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.schemaDriver
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
    /**
     * Maintainer 2026-10-30: the old episodes heard one by one before "heard up to" existed made the
     * progress document ~560 KB. After the update, a show heard from its oldest episode on gets its mark,
     * a smaller version goes out without the single entries it covers, and the other devices drop them too.
     */
    @Test
    fun singleEntriesFromBeforeHeardUpToAreFoldedIntoOne() = runBlocking<Unit> {
        val (phone, tablet) = pair()
        val list = phone.episodes.ofPodcast(phone.row(feed).id).first().sortedByDescending { it.publishedAt }
        phone.sync.syncNow()
        // As before 0.3.2: one by one, the oldest 300.
        phone.ms += 60_000
        list.takeLast(300).forEach { phone.episodes.setPlayed(it.id, true) }
        phone.sync.syncNow()
        assertTrue(phone.store.entries(SyncDocs.PROGRESS).size >= 300, "the large document as it was")
        tablet.ms = phone.ms + 1_000
        tablet.sync.syncNow()
        assertTrue(tablet.store.entries(SyncDocs.PROGRESS).size >= 300)

        // The update: at its next start the phone finds the run heard from the oldest on.
        phone.ms += 60_000
        assertEquals(1, phone.episodes.deriveHeardUpTo())
        assertEquals(null, phone.sync.syncNow().problem)
        val kept = phone.store.entries(SyncDocs.PROGRESS)
        assertTrue(kept.size <= 3, "the mark instead of 300 entries: ${kept.size}")
        assertTrue(kept.keys.any { it.startsWith("upto\n") })

        // The tablet reads the smaller version and drops its own copies; nothing it shows changes.
        tablet.ms = phone.ms + 1_000
        tablet.sync.syncNow()
        assertTrue(tablet.store.entries(SyncDocs.PROGRESS).size <= 3, "the tablet's copies: ${tablet.store.entries(SyncDocs.PROGRESS).size}")
        assertEquals(list.takeLast(300).map { it.guid }.toSet(),
            tablet.db.episodeQueries.selectByPodcast(tablet.row(feed).id).awaitAsList().filter { it.played == 1L }.map { it.guid }.toSet())

        // A new device takes all 300 from the one mark.
        val laptop = SyncDevice(relay, scope, nsec)
        laptop.ms = tablet.ms + 1_000
        laptop.podcasts.subscribe(feed)
        laptop.sync.enable()
        assertEquals(list.takeLast(300).map { it.guid }.toSet(),
            laptop.db.episodeQueries.selectByPodcast(laptop.row(feed).id).awaitAsList().filter { it.played == 1L }.map { it.guid }.toSet())
    }

    /** One old episode not heard: the mark stops before it, and every entry after it stays its own. */
    @Test
    fun theMarkStopsAtTheFirstEpisodeNotHeard() = runBlocking<Unit> {
        val (phone, _) = pair()
        val oldestFirst = phone.episodes.ofPodcast(phone.row(feed).id).first().sortedBy { it.publishedAt }
        phone.sync.syncNow()
        phone.ms += 60_000
        val gap = oldestFirst[150]
        oldestFirst.take(300).filter { it.id != gap.id }.forEach { phone.episodes.setPlayed(it.id, true) }
        phone.sync.syncNow()
        phone.ms += 60_000
        assertEquals(1, phone.episodes.deriveHeardUpTo())
        assertEquals(oldestFirst[149].publishedAt, phone.row(feed).played_through)
        phone.sync.syncNow()
        val kept = phone.store.entries(SyncDocs.PROGRESS).keys
        // The 150 before the gap in one mark; the 149 after it as they were.
        assertEquals(149, kept.count { it.startsWith("pos\n") })
        assertEquals(0L, phone.episode(feed, gap.guid).played)
    }

    /**
     * Review 2026-10-31: an episode taken back on the tablet after the phone's "this and older", before the
     * tablet heard of it — the mark made it heard there again (taking back leaves no time in the episode),
     * and the two devices stayed apart. The tablet's own entry is newer: it stands.
     */
    @Test
    fun takenBackAfterTheMarkWasMadeElsewhereStaysTakenBack() = runBlocking<Unit> {
        val (phone, tablet) = pair()
        val list = phone.episodes.ofPodcast(phone.row(feed).id).first().sortedByDescending { it.publishedAt }
        phone.ms += 60_000
        phone.episodes.setPlayed(phone.episode(feed, list[3].guid).id, true)
        phone.sync.syncNow()
        tablet.ms = phone.ms + 1_000
        tablet.sync.syncNow()
        assertEquals(1L, tablet.episode(feed, list[3].guid).played)

        phone.ms = tablet.ms + 60_000
        phone.episodes.setPlayedUpTo(list[1])
        phone.sync.syncNow()
        tablet.ms = phone.ms + 30_000
        tablet.episodes.setPlayed(tablet.episode(feed, list[3].guid).id, false)
        tablet.sync.syncNow()
        assertEquals(0L, tablet.episode(feed, list[3].guid).played, "taken back after the mark")
        assertEquals(1L, tablet.episode(feed, list[4].guid).played)

        phone.ms = tablet.ms + 60_000
        phone.sync.syncNow()
        assertEquals(0L, phone.episode(feed, list[3].guid).played)
        assertEquals(1L, phone.episode(feed, list[4].guid).played)
    }

    /**
     * Review 2026-10-31: "this and older" on an old episode after "all" re-dated the whole mark — the
     * other device then heard again an episode taken back in between, which the phone itself left unheard.
     * The mark stays as it was; what was heard now goes out one by one.
     */
    @Test
    fun thisAndOlderOnAnOldEpisodeAfterAllLeavesANewerOneTakenBack() = runBlocking<Unit> {
        val (phone, tablet) = pair()
        val list = phone.episodes.ofPodcast(phone.row(feed).id).first().sortedByDescending { it.publishedAt }
        phone.ms += 60_000
        phone.episodes.setShowPlayed(phone.row(feed).id)
        phone.sync.syncNow()
        tablet.ms = phone.ms + 1_000
        tablet.sync.syncNow()

        phone.ms = tablet.ms + 60_000
        phone.episodes.setPlayed(phone.episode(feed, list[2].guid).id, false)
        phone.episodes.setPlayed(phone.episode(feed, list[12].guid).id, false)
        phone.sync.syncNow()
        tablet.ms = phone.ms + 1_000
        tablet.sync.syncNow()
        assertEquals(0L, tablet.episode(feed, list[2].guid).played)
        assertEquals(0L, tablet.episode(feed, list[12].guid).played)

        phone.ms = tablet.ms + 60_000
        val before = phone.row(feed).played_through_at
        phone.episodes.setPlayedUpTo(list[10])
        assertEquals(before, phone.row(feed).played_through_at, "the mark as it was")
        phone.sync.syncNow()
        tablet.ms = phone.ms + 1_000
        tablet.sync.syncNow()
        assertEquals(0L, phone.episode(feed, list[2].guid).played)
        assertEquals(0L, tablet.episode(feed, list[2].guid).played, "newer than the episode marked: stays unheard")
        assertEquals(1L, tablet.episode(feed, list[12].guid).played, "older: heard, as on the phone")
    }

    /**
     * Review 2026-10-31: a mark found from the phone's copy of the feed — its newest 100, all heard — made
     * the tablet, subscribed long before and holding every episode, hear the older ones it never played.
     * The mark covers from where the phone's copy starts.
     */
    @Test
    fun aMarkFoundFromAShortCopyOfTheFeedCoversOnlyWhatThatCopyHeld() = runBlocking<Unit> {
        val raw = schemaDriver()
        val phone = SyncDevice(relay, scope, nsec, db = HoppDatabase(AsyncOnlyDriver(raw)))
        phone.podcasts.subscribe(feed)
        phone.sync.enable()
        val tablet = SyncDevice(relay, scope, nsec)
        tablet.ms += 1_000
        tablet.sync.enable()
        val all = tablet.episodes.ofPodcast(tablet.row(feed).id).first().sortedByDescending { it.publishedAt }
        // The phone's copy: the newest 100, as a feed that gives only those.
        raw.execute(null, "DELETE FROM episode WHERE published_at < ${all[99].publishedAt}", 0)
        val kept = phone.episodes.ofPodcast(phone.row(feed).id).first()
        assertTrue(kept.size in 100 until all.size, "${kept.size} of ${all.size}")

        // Heard one by one, as before 0.3.2; at the next start the phone finds its mark.
        phone.ms += 60_000
        kept.forEach { phone.episodes.setPlayed(it.id, true) }
        phone.sync.syncNow()
        phone.ms += 60_000
        assertEquals(1, phone.episodes.deriveHeardUpTo())
        assertEquals(null, phone.sync.syncNow().problem)

        val heard = kept.map { it.guid }.toSet()
        suspend fun heardOn(d: SyncDevice) = d.db.episodeQueries.selectByPodcast(d.row(feed).id).awaitAsList().filter { it.played == 1L }.map { it.guid }.toSet()
        tablet.ms = phone.ms + 1_000
        tablet.sync.syncNow()
        assertEquals(heard, heardOn(tablet))

        // A new device with the whole feed: the same.
        val laptop = SyncDevice(relay, scope, nsec)
        laptop.ms = tablet.ms + 1_000
        laptop.podcasts.subscribe(feed)
        laptop.sync.enable()
        assertEquals(heard, heardOn(laptop))
    }

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

    /**
     * Maintainer 2026-11-01: "4 episodes waiting" for good — the phone had older episodes than the
     * feed gives now. Once this device has read the feed after the entry was made, an episode not in
     * it is not waited for; it is still taken should the feed bring it after all.
     */
    @Test
    fun anEpisodeTheFeedNoLongerCarriesIsNotWaitedFor() = runBlocking<Unit> {
        val raw = schemaDriver()
        val phone = SyncDevice(relay, scope, nsec)
        phone.podcasts.subscribe(feed)
        phone.sync.enable()
        val tablet = SyncDevice(relay, scope, nsec, db = HoppDatabase(AsyncOnlyDriver(raw)))
        tablet.ms += 1_000
        tablet.podcasts.subscribe(feed)
        tablet.sync.enable()
        val old = phone.episodes.ofPodcast(phone.row(feed).id).first().minBy { it.publishedAt ?: 0 }.guid
        val dropped = { raw.execute(null, "DELETE FROM episode WHERE guid = '${old.replace("'", "''")}'", 0) }
        dropped()

        phone.ms += 60_000
        phone.episodes.savePosition(phone.episode(feed, old).id, 500_000, paused = true)
        phone.sync.syncNow()

        // The tablet read its feed before the phone listened: the episode may yet come.
        tablet.ms = phone.ms + 1_000
        assertEquals(1, tablet.sync.syncNow().pending)
        // Read again since, and not in it: nothing to wait for.
        tablet.ms += 1_000
        tablet.podcasts.refresh(tablet.row(feed).id)
        dropped()
        assertEquals(0, tablet.sync.syncNow().pending)
        // Back in the feed after all: taken.
        tablet.ms += 1_000
        tablet.podcasts.refresh(tablet.row(feed).id)
        tablet.sync.syncNow()
        assertEquals(500_000, tablet.episode(feed, old).position_ms)
    }

    /** Maintainer 2026-11-01: all shows heard at once from the library, one entry per show for sync. */
    @Test
    fun everyShowHeardAtOnceGoesToTheOtherDevice() = runBlocking<Unit> {
        val other = "https://pod.test/selfhosted.xml"
        val phone = SyncDevice(relay, scope, nsec)
        phone.podcasts.subscribe(feed)
        phone.podcasts.subscribe(other)
        phone.sync.enable()
        val tablet = SyncDevice(relay, scope, nsec)
        tablet.ms += 1_000
        tablet.sync.enable()

        phone.ms += 60_000
        phone.episodes.setAllPlayed()
        suspend fun all(d: SyncDevice) = d.urls().flatMap { d.db.episodeQueries.selectByPodcast(d.row(it).id).awaitAsList() }
        assertTrue(all(phone).all { it.played == 1L })
        phone.sync.syncNow()
        assertTrue(phone.store.entries(SyncDocs.PROGRESS).size <= 2, "${phone.store.entries(SyncDocs.PROGRESS).keys}")

        tablet.ms = phone.ms + 1_000
        tablet.sync.syncNow()
        val onTablet = all(tablet)
        assertEquals(2, tablet.urls().size)
        assertTrue(onTablet.isNotEmpty() && onTablet.all { it.played == 1L })
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
