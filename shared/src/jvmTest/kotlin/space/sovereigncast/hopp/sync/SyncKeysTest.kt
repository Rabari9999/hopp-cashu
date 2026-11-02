package space.sovereigncast.hopp.sync

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
import space.sovereigncast.hopp.testDatabase
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
        /** Entries this device cannot apply yet — a feed out of reach. */
        var notYet = emptySet<String>()
        var during: suspend () -> Unit = {}
        override suspend fun apply(entries: Map<String, SyncEntry>): Set<String> {
            during()
            val now = entries.filterKeys { it !in notYet }
            applied += now; return now.keys
        }
        override suspend fun snapshot(): Map<String, SyncEntry> = emptyMap()
    }

    /** A device whose listener signs in with [app] (Amber), or holds [nsec] here. */
    /** [secrets] and [db] from another: the same device started again. */
    private inner class Device(
        app: FakeSignerApp? = null, nsec: String? = null,
        val secrets: MemorySecretStore = MemorySecretStore(),
        val db: HoppDatabase = testDatabase(),
        signIn: Boolean = true,
    ) {
        var ms = nowSeconds * 1000
        val identity = IdentityRepository(secrets, RelayPublisher(relay), { ms / 1000 }, app, hoppSyncRelays = emptyList())
        val store = SyncStore(db) { ms }
        val keys = SyncKeys(identity, RelayPublisher(relay), secrets) { ms }
        val engine = SyncEngine(store, SyncRelays(RelayPublisher(relay)) { ms / 1000 }, identity, keys) { ms }
        val doc = Doc()
        val journal = SyncJournal(store)
        val sync = SyncManager(engine, store, journal, space.sovereigncast.hopp.data.SettingsRepository(db, journal), scope, { listOf(doc) }, { ms }, publishDelay = kotlin.time.Duration.parse("1h"))
        init {
            runBlocking {
                if (app != null && signIn) identity.signInWithSigner()
                if (nsec != null) identity.importKey(nsec)
            }
        }
        suspend fun record(key: String, title: String) = store.record("test", key, JsonObject(mapOf("title" to JsonPrimitive(title))))
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
        assertEquals(SyncOutcome.Done(0, true), phone.engine.sync(phone.doc, interactive = true))
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
        assertEquals(Renewal.Done, phone.sync.renewKey())
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
        assertEquals(Renewal.NotRenewed, phone.sync.renewKey())
        assertEquals(listOf(ring.id), keyrings(app.keys.publicKeyHex).map { it.id })
        assertEquals(SyncOutcome.Done(0, false), phone.engine.sync(phone.doc))
    }

    // ── delta audit 2026-10-22, HN-06 / MN-10 ────────────────────────────────

    /** Something from elsewhere this device cannot apply yet: the old key is not renewed away from it. */
    @Test
    fun aRenewalWaitsUntilNothingIsLeftWaiting() = runBlocking<Unit> {
        val app = FakeSignerApp()
        val phone = Device(app)
        phone.record("show-a", "A")
        phone.sync.enable()
        val ring = keyrings(app.keys.publicKeyHex).single()
        val tablet = Device(FakeSignerApp(app.keys))
        tablet.doc.notYet = setOf("show-a")
        tablet.sync.enable()
        assertEquals(1, tablet.sync.status.value.pending)
        tablet.ms += 2 * 60_000
        assertEquals(Renewal.NotRenewed, tablet.sync.renewKey())
        assertEquals(listOf(ring.id), keyrings(app.keys.publicKeyHex).map { it.id })
    }

    /** The new keyring goes out, the documents under it do not: the old key's documents stay whole, and
     *  it is not called done — a later round finishes it, and only then is the old one emptied. */
    @Test
    fun aRenewalWhoseDocumentsDoNotGoOutKeepsTheOldOnes() = runBlocking<Unit> {
        val app = FakeSignerApp()
        val phone = Device(app)
        phone.record("show-a", "A")
        phone.sync.enable()
        val old = phone.keys.signer(interactive = false)!!.pubkey
        phone.ms += 2 * 60_000
        // Only the listener's own events (the keyring) are taken.
        relay.refuse = { it.pubkey != app.keys.publicKeyHex }
        assertEquals(Renewal.Unfinished, phone.sync.renewKey())
        assertTrue(stored().filter { it.pubkey == old }.all { it.content.isNotEmpty() }, "the old documents were emptied")
        relay.refuse = { false }
        phone.ms += 2 * 60_000
        // One round sends them, the next reads them back — only then is the old key's copy emptied (delta audit 2, HN-06).
        phone.sync.syncNow()
        assertTrue(stored().filter { it.pubkey == old }.all { it.content.isNotEmpty() }, "emptied before the new documents were read back")
        phone.sync.syncNow()
        val fresh = Device(FakeSignerApp(app.keys))
        fresh.sync.enable()
        assertEquals(setOf("show-a"), fresh.doc.applied.keys)
        stored().filter { it.pubkey == old }.groupBy { e -> e.tags.first { it[0] == "d" }[1] }.values
            .forEach { versions -> assertEquals("", versions.maxBy { it.created_at }.content) }
    }

    // ── delta audit 2 2026-10-22 ─────────────────────────────────────────────

    private suspend fun oldIntact(old: String) = stored().filter { it.pubkey == old && it.kind == Nip78.KIND }
        .groupBy { e -> e.tags.first { it[0] == "d" }[1] }.values.all { versions -> versions.maxBy { it.created_at }.content.isNotEmpty() }

    /** HN-06, B02: the new documents are taken, but not read back: not done, and the old key's stay whole
     *  until a round reads the new ones back — after a restart too (ND-01). */
    @Test
    fun theOldDocumentsStayUntilTheNewOnesAreReadBack() = runBlocking<Unit> {
        val app = FakeSignerApp()
        val phone = Device(app)
        phone.record("show-a", "A")
        phone.sync.enable()
        val old = phone.keys.signer(interactive = false)!!.pubkey
        phone.ms += 2 * 60_000
        // Every read of what the new key sealed comes back empty.
        relay.hide = { it.pubkey != app.keys.publicKeyHex && it.pubkey != old }
        assertEquals(Renewal.Unfinished, phone.sync.renewKey())
        assertTrue(oldIntact(old), "the old documents were emptied before the new ones were read back")

        relay.hide = { false }
        val restarted = Device(app, secrets = phone.secrets, db = phone.db, signIn = false).also { it.ms = phone.ms + 2 * 60_000 }
        assertTrue(restarted.engine.movedPending(), "the restart forgot the old key")
        restarted.sync.syncNow()
        assertTrue(!oldIntact(old) && !restarted.engine.movedPending())
        val fresh = Device(FakeSignerApp(app.keys))
        fresh.sync.enable()
        assertEquals(setOf("show-a"), fresh.doc.applied.keys)
    }

    /** MN-10, B09: the old key's documents cannot be read when they are to go: not done — a failed read
     *  is not an empty one — and a later round retires them. */
    @Test
    fun aRetireWhoseReadFailsIsNotDone() = runBlocking<Unit> {
        val app = FakeSignerApp()
        val phone = Device(app)
        phone.record("show-a", "A")
        phone.sync.enable()
        val old = phone.keys.signer(interactive = false)!!.pubkey
        phone.ms += 2 * 60_000
        // The read of everything the old key sealed, as retiring it reads (no `d` asked for), fails.
        relay.failReads = { f -> f["authors"]?.toString()?.contains(old) == true && f["#d"] == null }
        assertEquals(Renewal.Unfinished, phone.sync.renewKey())
        assertTrue(oldIntact(old))
        assertTrue(phone.engine.movedPending())
        relay.failReads = { false }
        phone.ms += 2 * 60_000
        phone.sync.syncNow()
        assertTrue(!oldIntact(old) && !phone.engine.movedPending())
    }

    /** MN-02, B03: an entry dated days ahead is renewed along — under the new key, kept, and taken in on time. */
    @Test
    fun anEntryDatedAheadGoesAlongToTheNewKey() = runBlocking<Unit> {
        val app = FakeSignerApp()
        val tablet = Device(FakeSignerApp(app.keys)).also { it.ms += 3 * 86_400_000L }
        tablet.record("future", "F")
        tablet.sync.enable()
        val phone = Device(app).also { it.ms = tablet.ms - 3 * 86_400_000L + 1_000 }
        phone.record("show-a", "A")
        phone.sync.enable()
        phone.ms += 2 * 60_000
        assertEquals(Renewal.Done, phone.sync.renewKey())
        val later = Device(FakeSignerApp(app.keys)).also { it.ms = tablet.ms + 10 * 60_000 }
        later.sync.enable()
        assertEquals(setOf("future", "show-a"), later.doc.applied.keys)
    }

    /** MN-07: a round cut off is not shown as running for ever. */
    @Test
    fun aRoundCutOffIsNotRunningAnyMore() = runBlocking<Unit> {
        val app = FakeSignerApp()
        val tablet = Device(FakeSignerApp(app.keys))
        tablet.record("show-a", "A")
        tablet.sync.enable()
        val phone = Device(app)
        phone.sync.enable()
        tablet.ms += 60_000
        tablet.record("show-b", "B")
        tablet.sync.syncNow()
        phone.ms += 2 * 60_000
        phone.doc.during = { throw kotlinx.coroutines.CancellationException("app closed") }
        runCatching { phone.sync.syncNow() }
        assertEquals(false, phone.sync.status.value.running)
    }

    // ── delta audit 3 2026-10-22 ─────────────────────────────────────────────

    /** MN-08, E02: the relay keeps the new keyring but says it refused: not "nothing changed" — the device
     *  holds the new key, sends it again, and finishes the renewal later. */
    @Test
    fun aRenewalNoRelayConfirmedIsFinishedLater() = runBlocking<Unit> {
        val app = FakeSignerApp()
        val phone = Device(app)
        phone.record("show-a", "A")
        phone.sync.enable()
        val old = phone.keys.signer(interactive = false)!!.pubkey
        phone.ms += 2 * 60_000
        relay.passOnAndReject = true
        assertEquals(Renewal.Unfinished, phone.sync.renewKey())
        assertTrue(oldIntact(old))
        val restarted = Device(app, secrets = phone.secrets, db = phone.db, signIn = false).also { it.ms = phone.ms + 2 * 60_000 }
        assertTrue(restarted.engine.movedPending(), "the restart lost the renewal")
        relay.passOnAndReject = false
        restarted.sync.syncNow(); restarted.ms += 1_000; restarted.sync.syncNow()
        assertNotEquals(old, restarted.keys.signer(interactive = false)!!.pubkey)
        assertTrue(!oldIntact(old) && !restarted.engine.movedPending())
        val fresh = Device(FakeSignerApp(app.keys))
        fresh.sync.enable()
        assertEquals(setOf("show-a"), fresh.doc.applied.keys)
    }

    /** ND-04: a change made here shows as waiting at once, not only after the next round. */
    @Test
    fun aChangeMadeHereShowsAsWaitingAtOnce() = runBlocking<Unit> {
        val phone = Device(FakeSignerApp())
        phone.sync.enable()
        assertEquals(0, phone.sync.status.value.toSend)
        phone.journal.record("test", "show-b", JsonObject(mapOf("title" to JsonPrimitive("B"))))
        assertEquals(1, phone.sync.status.value.toSend)
        phone.sync.syncNow()
        assertEquals(0, phone.sync.status.value.toSend)
    }
}
