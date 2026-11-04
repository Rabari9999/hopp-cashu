package space.sovereigncast.hopp.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.nostr.Bech32
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.value.FakeRelay
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A change taken back is no change (maintainer 2026-11-04): a section hidden and shown again, an episode
 * moved and moved back, one taken out and put back — nothing waits to be sent, and no round asks the relays.
 */
class TakenBackTest {
    private val relay = FakeRelay()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nsec = Bech32.encode("nsec", KeyPair.generate().secretKey)
    private val feed = "https://pod.test/nodesignal.xml"

    @AfterTest fun stop() = scope.cancel()

    private suspend fun SyncDevice.ids() = db.episodeQueries.selectByPodcast(row(feed).id).awaitAsList().map { it.id }

    @Test
    fun aSettingChangedBackWaitsForNothing() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.sync.enable()
        val before = phone.store.entry(SyncDocs.SETTINGS, "home_hidden")
        phone.ms += 60_000
        phone.settings.update { it.copy(homeHidden = setOf("recent")) }
        assertTrue(phone.store.unpublished(SyncDocs.SETTINGS))
        phone.ms += 1_000
        phone.settings.update { it.copy(homeHidden = emptySet()) }
        assertFalse(phone.store.unpublished(SyncDocs.SETTINGS), "shown again, it still waits to be sent")
        // The relays' version again, with its own time — a later change elsewhere still wins over it.
        assertEquals(before, phone.store.entry(SyncDocs.SETTINGS, "home_hidden"))
    }

    @Test
    fun anEpisodeMovedAndMovedBackWaitsForNothing() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.podcasts.subscribe(feed)
        val (a, b, c) = phone.ids().take(3)
        listOf(a, b, c).forEach { phone.queue.playLast(it) }
        phone.sync.enable()
        phone.ms += 60_000
        phone.queue.reorder(listOf(b, a, c))
        assertTrue(phone.store.unpublished(SyncDocs.QUEUE))
        phone.queue.reorder(listOf(a, b, c))
        assertFalse(phone.store.unpublished(SyncDocs.QUEUE))
        // Taken out and put back with "Undo": the same.
        val taken = phone.queue.removeKeepingOrder(b)
        assertTrue(phone.store.unpublished(SyncDocs.QUEUE))
        phone.queue.putBack(b, taken)
        assertFalse(phone.store.unpublished(SyncDocs.QUEUE))
        assertEquals(listOf(a, b, c), phone.db.queueQueries.selectAll().awaitAsList())
    }

    /** No round at all: the relays hear nothing of a change taken back before it went out. */
    @Test
    fun aChangeTakenBackAsksNoRelay() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec, publishDelay = 300.milliseconds)
        phone.sync.enable()
        val heard = relay.stored().size
        var asked = 0
        relay.listeners += { asked++ }
        phone.settings.update { it.copy(homeHidden = setOf("recent")) }
        phone.settings.update { it.copy(homeHidden = emptySet()) }
        delay(1_500)
        assertEquals(0, asked, "something went to the relays")
        assertEquals(heard, relay.stored().size)
        // A real change still goes out.
        phone.ms += 60_000
        phone.settings.update { it.copy(homeHidden = setOf("recent")) }
        val deadline = System.currentTimeMillis() + 10_000
        while (asked == 0 && System.currentTimeMillis() < deadline) delay(50)
        assertTrue(asked > 0, "the change did not go out")
    }
}
