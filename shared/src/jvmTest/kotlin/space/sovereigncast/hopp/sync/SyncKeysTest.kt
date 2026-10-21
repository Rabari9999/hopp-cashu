package space.sovereigncast.hopp.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.sovereigncast.hopp.crypto.toHex
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.identity.FakeSignerApp
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.nostr.Bech32
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.nostr.LocalSigner
import space.sovereigncast.hopp.nostr.Nip78
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.nostr.UnsignedEvent
import space.sovereigncast.hopp.platform.MemorySecretStore
import space.sovereigncast.hopp.value.FakeRelay
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sync key (ADR 0021, 5, amended 2026-10-21): the listener's signer opens it once per device, never again. */
class SyncKeysTest {
    private val relay = FakeRelay()
    private val nowSeconds = 1_790_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun stop() = scope.cancel()

    private class Doc : SyncedDocument {
        override val name = "test"
        val applied = mutableMapOf<String, SyncEntry>()
        override suspend fun apply(entries: Map<String, SyncEntry>): Set<String> { applied += entries; return entries.keys }
        override suspend fun snapshot(): Map<String, SyncEntry> = emptyMap()
    }

    /** A device whose listener signs in with [app] (Amber), or holds [nsec] here. */
    private inner class Device(app: FakeSignerApp? = null, nsec: String? = null) {
        var ms = nowSeconds * 1000
        val secrets = MemorySecretStore()
        val identity = IdentityRepository(secrets, RelayPublisher(relay), { ms / 1000 }, app)
        val db = HoppDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HoppDatabase.Schema.create(it) })
        val store = SyncStore(db) { ms }
        val keys = SyncKeys(identity, RelayPublisher(relay), secrets) { ms }
        val engine = SyncEngine(store, SyncRelays(RelayPublisher(relay)) { ms / 1000 }, identity, keys) { ms }
        val doc = Doc()
        val journal = SyncJournal(store)
        val sync = SyncManager(engine, store, journal, space.sovereigncast.hopp.data.SettingsRepository(db, journal), scope, { listOf(doc) }, { ms }, publishDelay = kotlin.time.Duration.parse("1h"))
        init {
            runBlocking {
                if (app != null) identity.signInWithSigner()
                if (nsec != null) identity.importKey(nsec)
            }
        }
        fun record(key: String, title: String) = store.record("test", key, JsonObject(mapOf("title" to JsonPrimitive(title))))
    }

    private suspend fun stored(): List<Event> =
        RelayPublisher(relay).query(listOf(Json.parseToJsonElement("""{"kinds":[30078]}""") as JsonObject), listOf("wss://r"))

    private suspend fun keyrings(pubkey: String) = stored().filter { e -> e.pubkey == pubkey && e.tags.any { it == listOf("d", SyncKeys.DTAG) } }

    @Test
    fun aSignerAppIsAskedOncePerDeviceAndNeverInTheBackground() = runBlocking<Unit> {
        val listener = KeyPair.generate()
        val phoneApp = FakeSignerApp(listener)
        val phone = Device(phoneApp)
        phone.record("show-a", "A")

        // The first device makes the keyring: one encryption and one signature, with the listener there.
        assertEquals(SyncOutcome.Done(0, true), phone.engine.sync(phone.doc, interactive = true))
        assertEquals(listOf("nip44_encrypt", "sign_event:${Nip78.KIND}"), phoneApp.requests)

        // From then on the signer is not needed: rounds in the background, an hour later, go through.
        phoneApp.allowed = false
        phone.ms += 60 * 60_000
        phone.record("show-b", "B")
        assertEquals(SyncOutcome.Done(0, true), phone.engine.sync(phone.doc))
        assertEquals(2, phoneApp.requests.size)

        // A second device: before the listener confirms there is something sealed to restore …
        val tabletApp = FakeSignerApp(listener).apply { allowed = false }
        val tablet = Device(tabletApp)
        assertIs<SyncEngine.Peek.Sealed>(tablet.engine.peek("test"))
        assertIs<SyncOutcome.WaitsForSigner>(tablet.engine.sync(tablet.doc))
        // … one decryption to open the sync key, and the library is there.
        assertEquals(SyncOutcome.Done(2, false), tablet.engine.sync(tablet.doc, interactive = true))
        assertEquals(listOf("nip44_decrypt"), tabletApp.requests)
        assertEquals(setOf("show-a", "show-b"), tablet.doc.applied.keys)
        tablet.ms += 2 * 60 * 60_000
        tablet.record("show-c", "C")
        assertEquals(SyncOutcome.Done(0, true), tablet.engine.sync(tablet.doc))
        assertEquals(listOf("nip44_decrypt"), tabletApp.requests)

        // One keyring, by the listener; every document by the sync key, never the listener.
        assertEquals(1, keyrings(listener.publicKeyHex).size)
        val docs = stored().filter { e -> e.tags.any { it == listOf("d", "hopp/test") } }
        assertTrue(docs.isNotEmpty() && docs.none { it.pubkey == listener.publicKeyHex })
    }

    @Test
    fun aKeyringOutOfReachIsNotReplaced() = runBlocking<Unit> {
        val nsec = Bech32.encode("nsec", KeyPair.generate().secretKey)
        val phone = Device(nsec = nsec)
        phone.record("show-a", "A")
        assertEquals(SyncOutcome.Done(0, true), phone.engine.sync(phone.doc))
        val before = stored().size

        // A new phone while the relays drop every read: "none found" is not "none there".
        val fresh = Device(nsec = nsec)
        relay.readsFail = true
        val outcome = fresh.engine.sync(fresh.doc, interactive = true)
        assertIs<SyncOutcome.Failed>(outcome)
        assertIs<SyncKeyringNotKnown>(outcome.error)
        relay.readsFail = false
        assertEquals(before, stored().size, "something was published while the relays could not be read")

        // Reachable again: it opens the old keyring and the old library comes back.
        assertEquals(SyncOutcome.Done(1, false), fresh.engine.sync(fresh.doc, interactive = true))
        assertEquals(setOf("show-a"), fresh.doc.applied.keys)
    }

    @Test
    fun anUnreadableKeyringIsNeverReplaced() = runBlocking<Unit> {
        val listener = KeyPair.generate()
        relay.publish(Event.sign(listener, Nip78.KIND, "not sealed", listOf(listOf("d", SyncKeys.DTAG)), nowSeconds - 60))
        val phone = Device(nsec = Bech32.encode("nsec", listener.secretKey))
        phone.record("show-a", "A")
        assertIs<SyncOutcome.Failed>(phone.engine.sync(phone.doc, interactive = true))
        assertEquals(listOf("not sealed"), keyrings(listener.publicKeyHex).map { it.content })
    }

    @Test
    fun aKeyringMadeElsewhereAtTheSameTimeWinsAndNothingIsLost() = runBlocking<Unit> {
        val listener = KeyPair.generate()
        val nsec = Bech32.encode("nsec", listener.secretKey)
        val phone = Device(nsec = nsec)
        phone.record("show-a", "A")
        phone.engine.sync(phone.doc)
        val first = phone.keys.signer(interactive = false)!!.pubkey

        // Another device made its own keyring a moment later, before it saw the phone's.
        val other = KeyPair.generate()
        val me = LocalSigner(listener)
        val content = me.encryptToSelf("""{"v":1,"key":"${other.secretKey.toHex()}"}""")
        relay.publish(me.sign(UnsignedEvent(Nip78.KIND, content, listOf(listOf("d", SyncKeys.DTAG)), nowSeconds + 5)))

        // The phone looks again within a few minutes, follows it and brings its entries along.
        phone.ms += SyncKeys.CHECK_EVERY.inWholeMilliseconds + 10_000
        phone.record("show-b", "B")
        assertEquals(SyncOutcome.Done(0, true), phone.engine.sync(phone.doc))
        val now = phone.keys.signer(interactive = false)!!.pubkey
        assertNotEquals(first, now)
        assertEquals(other.publicKeyHex, now)

        val fresh = Device(nsec = nsec)
        fresh.engine.sync(fresh.doc)
        assertEquals(setOf("show-a", "show-b"), fresh.doc.applied.keys)
    }

    @Test
    fun turningSyncOffForgetsTheKeyHere() = runBlocking<Unit> {
        val device = SyncDevice(relay, scope, Bech32.encode("nsec", KeyPair.generate().secretKey))
        device.sync.enable()
        assertTrue(device.secrets.get("nostr_sync_key") != null)
        device.sync.disable()
        assertNull(device.secrets.get("nostr_sync_key"))
    }

    // ── a new sync key (ADR 0022, 3) ──────────────────────────────────────────

    @Test
    fun aRenewedKeyLocksOutTheOldOneAndTheOtherDevicesFollow() = runBlocking<Unit> {
        val listener = KeyPair.generate()
        val phoneApp = FakeSignerApp(listener); val tabletApp = FakeSignerApp(listener); val browserApp = FakeSignerApp(listener)
        val phone = Device(phoneApp); val tablet = Device(tabletApp); val browser = Device(browserApp)
        phone.record("show-a", "A")
        phone.sync.enable(); tablet.sync.enable(); browser.sync.enable()
        assertEquals(setOf("show-a"), browser.doc.applied.keys)
        val old = phone.keys.signer(interactive = false)!!.pubkey
        assertEquals(old, browser.keys.signer(interactive = false)!!.pubkey)

        // The browser is gone; the phone renews. Its signer is asked as for the first keyring.
        phone.ms += 2 * 60_000
        phone.record("show-b", "B")
        val asked = phoneApp.requests.size
        assertTrue(phone.sync.renewKey())
        assertEquals(listOf("nip44_encrypt", "sign_event:${Nip78.KIND}"), phoneApp.requests.drop(asked))
        assertNotEquals(old, phone.keys.signer(interactive = false)!!.pubkey)

        // What the old key sealed is empty on the relays, and deleted (NIP-09).
        val byOld = stored().filter { it.pubkey == old }
        assertTrue(byOld.isNotEmpty())
        byOld.groupBy { e -> e.tags.first { it[0] == "d" }[1] }.values
            .forEach { versions -> assertEquals("", versions.maxBy { it.created_at }.content) }
        val deletions = RelayPublisher(relay).query(listOf(Json.parseToJsonElement("""{"kinds":[5],"authors":["$old"]}""") as JsonObject), listOf("wss://r"))
        assertTrue(deletions.single().tags.contains(listOf("a", "${Nip78.KIND}:$old:hopp/test")))

        // The browser's session, still holding the old key, gets nothing new without the listener.
        browserApp.allowed = false
        browser.ms += 2 * 60_000
        assertIs<SyncOutcome.WaitsForSigner>(browser.engine.sync(browser.doc))
        assertTrue("show-b" !in browser.doc.applied)

        // The tablet asks its signer once, then has it all again.
        tabletApp.allowed = false
        tablet.ms += 2 * 60_000
        assertIs<SyncOutcome.WaitsForSigner>(tablet.engine.sync(tablet.doc))
        val before = tabletApp.requests.size
        assertEquals(SyncOutcome.Done(1, false), tablet.engine.sync(tablet.doc, interactive = true))
        assertEquals(listOf("nip44_decrypt"), tabletApp.requests.drop(before))
        assertEquals(setOf("show-a", "show-b"), tablet.doc.applied.keys)
    }

    @Test
    fun aRenewalTheSignerRefusesChangesNothing() = runBlocking<Unit> {
        val app = FakeSignerApp()
        val phone = Device(app)
        phone.record("show-a", "A")
        phone.sync.enable()
        val ring = keyrings(app.keys.publicKeyHex).single()
        app.reject = true
        assertEquals(false, phone.sync.renewKey())
        assertEquals(listOf(ring.id), keyrings(app.keys.publicKeyHex).map { it.id })
        assertEquals(SyncOutcome.Done(0, false), phone.engine.sync(phone.doc))
    }
}
