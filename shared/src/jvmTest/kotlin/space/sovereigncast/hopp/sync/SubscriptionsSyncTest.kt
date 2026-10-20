package space.sovereigncast.hopp.sync

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
