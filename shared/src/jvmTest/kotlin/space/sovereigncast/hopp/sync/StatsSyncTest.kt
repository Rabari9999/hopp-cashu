package space.sovereigncast.hopp.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
}
