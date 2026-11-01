package space.sovereigncast.hopp.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.nostr.Bech32
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.value.FakeRelay
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Restore on a new device (package 6 of docs/tasks/nostr-sync.md). */
class RestoreTest {
    private val relay = FakeRelay()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nsec = Bech32.encode("nsec", KeyPair.generate().secretKey)
    private val feed = "https://pod.test/nodesignal.xml"

    @AfterTest fun stop() = scope.cancel()

    @Test
    fun aNewDeviceIsOfferedTheLibraryAndGetsItWhole() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.podcasts.subscribe(feed)
        phone.podcasts.subscribe("https://pod.test/selfhosted.xml")
        val guids = phone.db.episodeQueries.selectByPodcast(phone.row(feed).id).awaitAsList().map { it.guid }
        phone.episodes.savePosition(phone.episode(feed, guids[0]).id, 60_000)
        phone.episodes.savePosition(phone.episode(feed, guids[1]).id, 90_000)
        phone.episodes.setPlayed(phone.episode(feed, guids[2]).id, true)
        phone.queue.playLast(phone.episode(feed, guids[3]).id)
        phone.settings.update { it.copy(skipForwardSeconds = 60) }
        phone.sync.enable()

        // Hopp installed anew, the key brought back: an offer, with what it holds.
        val fresh = SyncDevice(relay, scope, nsec)
        fresh.ms += 60_000
        assertEquals(RestoreOffer(2, 2), fresh.sync.restoreOffer())
        fresh.sync.enable()
        assertEquals(phone.urls(), fresh.urls())
        assertEquals(90_000, fresh.episode(feed, guids[1]).position_ms)
        assertEquals(1L, fresh.episode(feed, guids[2]).played)
        assertEquals(listOf(guids[3]), fresh.queueGuids())
        assertEquals(60, fresh.settings.current().skipForwardSeconds)
        // Sync is on now: no offer any more.
        assertNull(fresh.sync.restoreOffer())
    }

    @Test
    fun nothingOnTheRelaysNoOffer() = runBlocking<Unit> {
        assertNull(SyncDevice(relay, scope, nsec).sync.restoreOffer())
    }
}
