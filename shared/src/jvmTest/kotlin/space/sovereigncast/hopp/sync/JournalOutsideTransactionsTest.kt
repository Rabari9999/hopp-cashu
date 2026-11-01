package space.sovereigncast.hopp.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

/**
 * Review 2026-10-31: a show's speed, skips and automatic downloads, and giving it up, told sync of the
 * change from inside their database transaction. Sync's answer may suspend, and on the apps' drivers a
 * transaction belongs to its thread: resumed on another, the rest of it would run outside it.
 */
class JournalOutsideTransactionsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @AfterTest fun stop() = scope.cancel()

    @Test
    fun syncIsToldOfAShowsChangesOnlyOutsideATransaction() = runBlocking<Unit> {
        val raw = schemaDriver()
        val device = SyncDevice(FakeRelay(), scope, Bech32.encode("nsec", KeyPair.generate().secretKey), db = HoppDatabase(AsyncOnlyDriver(raw)))
        device.podcasts.subscribe("https://pod.test/nodesignal.xml")
        val id = device.row("https://pod.test/nodesignal.xml").id
        val told = mutableListOf<Boolean>()
        device.journal.enabled = true
        device.journal.onChange = { _, _ -> told += raw.currentTransaction() != null }
        device.podcasts.setSpeed(id, 1.5f)
        device.podcasts.setAutoDownload(id, false)
        device.podcasts.setSkip(id, introSeconds = 30)
        device.podcasts.unsubscribe(id)
        assertEquals(List(4) { false }, told, "told inside a transaction: $told")
    }
}
