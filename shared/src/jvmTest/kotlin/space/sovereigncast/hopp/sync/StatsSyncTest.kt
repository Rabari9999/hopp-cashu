package space.sovereigncast.hopp.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.datetime.toInstant
import space.sovereigncast.hopp.data.EpisodeItem
import space.sovereigncast.hopp.data.StatsRange
import space.sovereigncast.hopp.nostr.Bech32
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.value.FakeRelay
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Listening statistics (maintainer 2026-10-26): counted per device, added up across devices by sync. */
class StatsSyncTest {
    private val relay = FakeRelay()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nsec = Bech32.encode("nsec", KeyPair.generate().secretKey)
    private val feed = "https://pod.test/nodesignal.xml"

    @AfterTest fun stop() = scope.cancel()

    private fun SyncDevice.item(n: Int): EpisodeItem = runBlocking {
        val id = row(feed).id
        episodes.ofPodcastPage(id, 10).first()[n]
    }

    /** [minutes] heard of [item] at [speed]: the clock's time, and the episode's. */
    private suspend fun SyncDevice.listen(item: EpisodeItem, minutes: Int, speed: Double = 1.0) {
        repeat(minutes * 12) { listening.heard(item, 5_000, (5_000 * speed).toLong()) }
    }

    @Test
    fun aDeviceCountsWhatItHears() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.podcasts.subscribe(feed)
        val a = phone.item(0); val b = phone.item(1)
        phone.listen(a, 30, speed = 1.5)
        phone.listen(b, 10)
        phone.listening.skipped(a, 45_000)
        phone.listening.counted(chapters = 3, transcripts = 1, comments = 2)
        phone.listening.flush()
        val s = phone.listening.stats(StatsRange.Month)
        assertEquals(40 * 60_000L, s.heardMs)
        assertEquals(15 * 60_000L, s.savedBySpeedMs, "half again of 30 minutes")
        assertEquals(45_000L, s.savedBySkipsMs)
        assertEquals(2, s.episodes)
        assertEquals(listOf(a.title, b.title).map { it.take(80) }, s.topEpisodes.map { it.title }, "titles kept short")
        assertEquals(1, s.topShows.size)
        assertEquals(40 * 60_000L, s.bars.last().heardMs, "today's bar")
        assertEquals(1, s.longestStreakDays)
        assertEquals(Triple(3L, 1L, 2L), Triple(s.chapters, s.transcripts, s.comments))
        // Another day, then a day without: the streak counts days in a row.
        phone.ms += 86_400_000; phone.listen(a, 6); phone.listening.flush()
        phone.ms += 2 * 86_400_000; phone.listen(a, 6); phone.listening.flush()
        assertEquals(2, phone.listening.stats(StatsRange.All).longestStreakDays)
    }

    /** Phone and tablet each count their own; with sync, each sees both together — and nothing is
     *  counted twice, however often they sync. */
    @Test
    fun devicesAddUpAndNeverOverwriteEachOther() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.podcasts.subscribe(feed)
        phone.sync.enable()
        val tablet = SyncDevice(relay, scope, nsec)
        tablet.ms += 1_000
        tablet.sync.enable()
        assertNotEquals(phone.listening.device, tablet.listening.device)

        phone.listen(phone.item(0), 20); phone.listening.flush(force = true)
        tablet.listen(tablet.item(1), 10); tablet.listening.flush(force = true)
        repeat(2) {
            phone.ms += 60_000; phone.sync.syncNow()
            tablet.ms += 60_000; tablet.sync.syncNow()
        }
        phone.ms += 60_000; phone.sync.syncNow()
        for (d in listOf(phone, tablet)) {
            val s = d.listening.stats(StatsRange.All)
            assertEquals(30 * 60_000L, s.heardMs, "both devices, once each")
            assertEquals(2, s.episodes)
        }
        // Each wrote only keys with its own name.
        val keys = phone.store.entries(SyncDocs.STATS).keys
        assertTrue(keys.all { StatsEntries.deviceOf(it) in setOf(phone.listening.device, tablet.listening.device) })
        // Later listening on the phone: more, not again.
        phone.ms += 31 * 60_000; phone.listen(phone.item(0), 5); phone.ms += 6 * 60_000; phone.listening.flush()
        phone.sync.syncNow()
        tablet.ms += 40 * 60_000; tablet.sync.syncNow()
        assertEquals(35 * 60_000L, tablet.listening.stats(StatsRange.All).heardMs)
    }

    /** Review 2026-10-27: what was given shows without sync too, and once — also after the show
     *  was unsubscribed and its payments fell back to its title. */
    @Test
    fun whatWasGivenIsCountedOnceWhenTheShowGoes() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.podcasts.subscribe(feed)
        val id = phone.row(feed).id
        phone.db.paymentQueries.insert(phone.ms / 1000, "boost", id, null, "Nodesignal", null, 1_000_000, null, 0, 0, "[]")
        assertEquals(1_000_000L, phone.listening.stats(StatsRange.All).boostMsat, "without sync")
        phone.podcasts.unsubscribe(id)
        assertEquals(1_000_000L, phone.listening.stats(StatsRange.All).boostMsat, "once, not under both keys")
    }

    /** Device check 2026-10-27: with sync off as well, going to the background writes what was
     *  heard — an app ended from there kept nothing of its last half minute. */
    @Test
    fun theBackgroundWritesWhatWasHeardWithSyncOff() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.podcasts.subscribe(feed)
        phone.listening.heard(phone.item(0), 10_000, 10_000)
        phone.sync.onBackground { phone.listening.flush(force = true) }
        kotlinx.coroutines.withTimeout(5_000) {
            while (phone.db.listeningQueries.showTotals(0, 999_912).executeAsList().sumOf { it.heard ?: 0 } == 0L) kotlinx.coroutines.delay(10)
        }
    }

    /** Review 2026-10-27: a day sync dropped as too old is not noted again with every note after. */
    @Test
    fun whatSyncDropsAsTooOldIsNotNotedAgain() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.podcasts.subscribe(feed)
        phone.sync.enable()
        phone.listen(phone.item(0), 10); phone.listening.flush(force = true)
        phone.ms += 60_000; phone.sync.syncNow()
        val day = StatsEntries.dayKey(phone.listening.device, phone.listening.dayAt(phone.ms))
        phone.ms += 402 * 86_400_000L
        phone.sync.syncNow()
        phone.listen(phone.item(1), 10); phone.listening.flush(force = true)
        assertTrue(day !in phone.store.pendingKeys(SyncDocs.STATS))
        assertTrue(phone.listening.stats(StatsRange.All).heardMs == 20 * 60_000L, "kept here all the same")
    }

    /** What another device sends is taken only in Hopp's shape and bounds. */
    @Test
    fun oddCountsFromElsewhereAreNotTaken() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        val doc = StatsDocument(phone.db, phone.listening) { phone.ms }
        val month = 202610
        fun e(vararg kv: Pair<String, Long>) = SyncEntry(1, d = buildJsonObject { kv.forEach { (k, v) -> put(k, v) } })
        doc.apply(mapOf(
            StatsEntries.showKey("abcd", month, "url:x") to e("h" to 600, "b" to 21_000),
            StatsEntries.showKey("abcd", month, "url:y") to e("h" to -5),
            StatsEntries.showKey("abcd", month, "url:z") to e("h" to Long.MAX_VALUE / 1000),
            StatsEntries.showKey("NOT-A-DEVICE", month, "url:x") to e("h" to 600),
            StatsEntries.showKey("abcd", 202613, "url:x") to e("h" to 600),
            StatsEntries.dayKey("abcd", 20_000) to e("h" to 30 * 3600),
        ))
        val shows = phone.db.listeningQueries.showsOf("abcd").executeAsList()
        assertEquals(mapOf("url:x" to 600_000L, "url:y" to 0L, "url:z" to 0L), shows.associate { it.show to it.heard_ms })
        assertEquals(21_000L, shows.single { it.show == "url:x" }.boost_msat)
        assertTrue(phone.db.listeningQueries.daysOf("abcd").executeAsList().isEmpty(), "more than a day in a day")
    }

    /** Days leave sync after 400 local days, as they were noted — not by the UTC date
     *  (Zetti's beta test 2026-10-27): just after midnight in Kiritimati (UTC+14) it is still the day before in UTC. */
    @Test
    fun daysExpireByTheLocalDate() {
        val phone = SyncDevice(relay, scope, nsec)
        val local = space.sovereigncast.hopp.data.ListeningRepository(phone.db, phone.secrets, { 0 }) { kotlinx.datetime.TimeZone.of("Pacific/Kiritimati") }
        val doc = StatsDocument(phone.db, local) { 0 }
        // 2026-10-27 00:30 in Kiritimati = 2026-10-26 10:30 UTC.
        val now = kotlinx.datetime.LocalDateTime(2026, 10, 27, 0, 30).toInstant(kotlinx.datetime.TimeZone.of("Pacific/Kiritimati")).toEpochMilliseconds()
        val today = kotlinx.datetime.LocalDate(2026, 10, 27).toEpochDays()
        val entry = SyncEntry(1)
        assertTrue(doc.expired(StatsEntries.dayKey("abcd", today - 401), entry, now))
        assertTrue(!doc.expired(StatsEntries.dayKey("abcd", today - 400), entry, now), "the 400th day before today stays")
    }
}
