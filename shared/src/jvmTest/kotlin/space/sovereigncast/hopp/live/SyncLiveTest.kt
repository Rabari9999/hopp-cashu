package space.sovereigncast.hopp.live

import app.cash.sqldelight.async.coroutines.synchronous
import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.net.createHttpClient
import space.sovereigncast.hopp.nostr.KtorRelayConnector
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.platform.MemorySecretStore
import space.sovereigncast.hopp.sync.SyncDocs
import space.sovereigncast.hopp.sync.SyncEngine
import space.sovereigncast.hopp.sync.SyncKeys
import space.sovereigncast.hopp.sync.SyncRelays
import space.sovereigncast.hopp.sync.SyncStore
import kotlin.test.Test

/** What the real relays hold of a test key's sync documents — the device check's
 *  key: `HOPP_LIVE_SYNC_KEY=<ncryptsec or nsec> HOPP_LIVE_SYNC_PASSWORD=…`. */
class SyncLiveTest {
    @Test
    fun theDocumentsOfATestKey() = runBlocking<Unit> {
        val key = System.getenv("HOPP_LIVE_SYNC_KEY") ?: return@runBlocking
        val http = createHttpClient(AppInfo("0.0.1", "JVM", "Test"))
        val publisher = RelayPublisher(KtorRelayConnector(http))
        val now = { System.currentTimeMillis() / 1000 }
        val secrets = MemorySecretStore()
        val identity = IdentityRepository(secrets, publisher, now)
        identity.importKey(key, System.getenv("HOPP_LIVE_SYNC_PASSWORD"))
        println("relays: " + identity.relays())
        println("write: " + identity.writeRelays())
        val db = space.sovereigncast.hopp.db.HoppDatabase(app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY).also { space.sovereigncast.hopp.db.HoppDatabase.Schema.synchronous().create(it) })
        // Read only: without a keyring none is made here.
        val keys = SyncKeys(identity, publisher, secrets) { System.currentTimeMillis() }
        val engine = SyncEngine(SyncStore(db) { System.currentTimeMillis() }, SyncRelays(publisher, now), identity, keys) { System.currentTimeMillis() }
        val signer = keys.signer(interactive = true, create = false)
        println("sync key: " + (signer?.pubkey ?: "none — no keyring on the relays"))
        for (doc in listOf(SyncDocs.SUBSCRIPTIONS, SyncDocs.PROGRESS, SyncDocs.QUEUE, SyncDocs.SETTINGS)) {
            if (signer == null) break
            val r = runCatching { SyncRelays(publisher, now).fetch(signer, doc, identity.writeRelays()) }
            println("$doc: " + r.map { it::class.simpleName }.getOrElse { "ERROR $it" })
            println("  peek: " + engine.peek(doc)?.let { it::class.simpleName })
        }
    }
}
