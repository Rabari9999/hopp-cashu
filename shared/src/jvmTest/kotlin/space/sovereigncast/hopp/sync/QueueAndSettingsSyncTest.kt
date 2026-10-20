package space.sovereigncast.hopp.sync

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

/** "Up next" and listening habits in step over Nostr (package 5 of docs/tasks/nostr-sync.md). */
class QueueAndSettingsSyncTest {
    private val relay = FakeRelay()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nsec = Bech32.encode("nsec", KeyPair.generate().secretKey)
    private val feed = "https://pod.test/nodesignal.xml"

    @AfterTest fun stop() = scope.cancel()

    private fun SyncDevice.id(guid: String) = episode(feed, guid).id
    private fun guids(d: SyncDevice) = d.db.episodeQueries.selectByPodcast(d.row(feed).id).executeAsList().map { it.guid }

    @Test
    fun upNextFollowsAndAReorderAndAnAdditionBothSurvive() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.podcasts.subscribe(feed)
        val (a, b, c, d) = guids(phone).take(4)
        phone.queue.playLast(phone.id(a))
        phone.queue.playLast(phone.id(b))
        phone.sync.enable()
        val tablet = SyncDevice(relay, scope, nsec)
        tablet.ms += 1_000
        tablet.sync.enable()
        assertEquals(listOf(a, b), tablet.queueGuids())

        // The phone moves b to the front, the tablet adds c at the end — before either syncs.
        phone.ms += 60_000
        phone.queue.reorder(listOf(phone.id(b), phone.id(a)))
        tablet.ms += 61_000
        tablet.queue.playLast(tablet.id(c))
        phone.sync.syncNow(); tablet.sync.syncNow(); phone.sync.syncNow()
        assertEquals(listOf(b, a, c), phone.queueGuids())
        assertEquals(listOf(b, a, c), tablet.queueGuids())

        // Played on the phone: out of the queue there, and on the tablet.
        phone.ms += 60_000
        phone.queue.pop()
        phone.queue.playNext(phone.id(d))
        phone.sync.syncNow()
        tablet.ms += 120_000
        tablet.sync.syncNow()
        assertEquals(listOf(d, a, c), tablet.queueGuids())
    }

    @Test
    fun listeningHabitsFollowTheWalletAndBudgetStay() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.settings.update { it.copy(skipForwardSeconds = 45, defaultSpeed = 1.3f, senderName = "tg", dailyBudgetSats = 21_000, storageLimitMb = 0) }
        phone.sync.enable()

        // A tablet already in use: its own habits give way to the phone's, its budget stays its own.
        val tablet = SyncDevice(relay, scope, nsec)
        tablet.settings.update { it.copy(skipForwardSeconds = 15, dailyBudgetSats = 1_000) }
        tablet.ms += 1_000
        tablet.sync.enable()
        val t = tablet.settings.current()
        assertEquals(45, t.skipForwardSeconds)
        assertEquals(1.3f, t.defaultSpeed)
        assertEquals("tg", t.senderName)
        assertEquals(1_000, t.dailyBudgetSats)
        assertEquals(2_048, t.storageLimitMb)

        // Changed later on the tablet: the phone follows.
        tablet.ms += 60_000
        tablet.settings.update { it.copy(skipBackSeconds = 30) }
        tablet.sync.syncNow()
        phone.ms += 120_000
        phone.sync.syncNow()
        assertEquals(30, phone.settings.current().skipBackSeconds)
        assertEquals(21_000, phone.settings.current().dailyBudgetSats)
    }
}
