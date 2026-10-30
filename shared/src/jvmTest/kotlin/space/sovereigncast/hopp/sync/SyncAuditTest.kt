package space.sovereigncast.hopp.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.sovereigncast.hopp.data.SubscribeResult
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.nostr.Bech32
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.nostr.Nip78
import space.sovereigncast.hopp.nostr.RelayConnector
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.nostr.RelaySession
import space.sovereigncast.hopp.platform.MemorySecretStore
import space.sovereigncast.hopp.testDatabase
import space.sovereigncast.hopp.value.FakeRelay
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * The findings of the sync audit of 2026-10-22 (Hopp-0.3.0-Sync-Astra-Audit),
 * each as it was reproduced there, with what must happen instead.
 */
class SyncAuditTest {
    private val nowSeconds = 1_790_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nsec = Bech32.encode("nsec", KeyPair.generate().secretKey)

    @AfterTest fun stop() = scope.cancel()

    /** The listener's three write relays, each a relay of its own; one may be down. */
    private inner class Relays : RelayConnector {
        val byUrl = IdentityRepository.DEFAULT_RELAYS.associateWith { FakeRelay() }.toMutableMap()
        val down = mutableSetOf<String>()
        override suspend fun connect(url: String): RelaySession {
            if (url in down) throw IllegalStateException("down")
            return (byUrl[url] ?: throw IllegalStateException("unknown relay")).connect(url)
        }
        operator fun get(i: Int) = IdentityRepository.DEFAULT_RELAYS[i]
        suspend fun all(): List<Event> = byUrl.values.flatMap { r ->
            RelayPublisher(r).query(listOf(Json.parseToJsonElement("""{"kinds":[30078]}""") as JsonObject), listOf("wss://r"))
        }
    }

    private class Doc : SyncedDocument {
        override val name = "test"
        val applied = mutableMapOf<String, SyncEntry>()
        var during: suspend () -> Unit = {}
        /** After the entries are in the app. */
        var after: suspend () -> Unit = {}
        override suspend fun apply(entries: Map<String, SyncEntry>): Set<String> { during(); applied += entries; after(); return entries.keys }
        override suspend fun snapshot(): Map<String, SyncEntry> = emptyMap()
        /** Entries no device needs any more. */
        var expire: (String) -> Boolean = { false }
        override fun expired(key: String, entry: SyncEntry, nowMs: Long) = expire(key)
    }

    /** [secrets]: another's — the same device started again. */
    private inner class Device(val relays: RelayConnector, val secrets: MemorySecretStore = MemorySecretStore()) {
        var ms = nowSeconds * 1000
        val identity = IdentityRepository(secrets, RelayPublisher(relays), { ms / 1000 }).also { runBlocking { it.importKey(nsec) } }
        val db = testDatabase()
        val store = SyncStore(db) { ms }
        val keys = SyncKeys(identity, RelayPublisher(relays), secrets) { ms }
        val engine = SyncEngine(store, SyncRelays(RelayPublisher(relays)) { ms / 1000 }, identity, keys) { ms }
        val doc = Doc()
        suspend fun record(key: String, v: String, deleted: Boolean = false) = store.record("test", key, JsonObject(mapOf("v" to JsonPrimitive(v))), deleted)
        /** The listener changes [key] here: in the app and in the store, as the repositories do. */
        suspend fun change(key: String, v: String) { ms += 1_000; doc.applied[key] = record(key, v) }
        suspend fun syncKey() = keys.signer(interactive = false, create = false)?.pubkey
    }

    private fun Event.d() = tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)

    /** The maintainer's relays on 2026-10-30, two answering: an old version whole on one, its first two parts
     *  only on the other; a newer, larger one cut off after its second part on both; nothing kept of it. */
    @Test
    fun theMaintainersCutOffVersionIsWrittenOverAfterTenMinutes() = runBlocking<Unit> {
        val relays = Relays()
        relays.down += relays[2]
        val phone = Device(relays)
        val blob = "x".repeat(1_000)
        repeat(400) { phone.record("k$it", blob) }
        val partNo = { e: Event -> e.d()?.substringAfter('#', "1")?.toIntOrNull() ?: 1 }
        relays.byUrl.getValue(relays[1]).refuse = { partNo(it) >= 3 }
        assertIs<SyncOutcome.Failed>(phone.engine.sync(phone.doc, interactive = true))
        phone.ms += 2.minutes.inWholeMilliseconds
        repeat(160) { phone.record("n$it", blob) }
        relays.byUrl.values.forEach { r -> r.refuse = { partNo(it) >= 3 } }
        assertIs<SyncOutcome.Failed>(phone.engine.sync(phone.doc))
        relays.byUrl.values.forEach { r -> r.refuse = { false } }
        phone.store.markBroken("test", null)

        phone.ms += 1_000
        assertIs<SyncOutcome.Incomplete>(phone.engine.sync(phone.doc))
        phone.ms += 4.minutes.inWholeMilliseconds
        val repaired = phone.engine.sync(phone.doc)
        assertIs<SyncOutcome.Done>(repaired, "repaired: $repaired")
    }

    /** Maintainer 2026-10-30: progress shrank to one part of 8 KB, and 13 old parts of ~700 KB stayed and were
     *  read every round. A smaller version overwrites the larger one's later parts with empty ones. */
    @Test
    fun aSmallerVersionLeavesNoLargePartsBehind() = runBlocking<Unit> {
        val relays = Relays()
        val phone = Device(relays)
        val blob = "x".repeat(1_000)
        repeat(100) { phone.record("k$it", blob) }
        phone.engine.sync(phone.doc, interactive = true)
        assertTrue(relays.all().any { it.d() == "hopp/test#3" }, "the test needs three parts")
        phone.doc.expire = { it != "k0" }
        phone.change("k0", "small")
        assertIs<SyncOutcome.Done>(phone.engine.sync(phone.doc))
        // As a relay keeps them: the newest per part (NIP-01, replaceable by its d tag).
        val later = relays.all().filter { it.d()?.startsWith("hopp/test#") == true }.groupBy { it.d() }.values.map { v -> v.maxBy { it.created_at } }
        assertTrue(later.size == 2 && later.all { it.content.length < 1_000 }, "parts 2 and 3 small now: ${later.map { it.content.length }}")
        val tablet = Device(relays).also { it.ms = phone.ms + 1_000 }
        assertIs<SyncOutcome.Done>(tablet.engine.sync(tablet.doc, interactive = true))
        assertEquals(setOf("k0"), tablet.doc.applied.keys)
    }

    /** "Write anew from this device" on the sync page: at once, whatever the read found. */
    @Test
    fun theListenerHasAPartReadDocumentWrittenAnewAtOnce() = runBlocking<Unit> {
        val relays = Relays()
        val phone = Device(relays)
        val blob = "x".repeat(1_000)
        repeat(100) { phone.record("k$it", blob) }
        val partNo = { e: Event -> e.d()?.substringAfter('#', "1")?.toIntOrNull() ?: 1 }
        relays.byUrl.values.forEach { r -> r.refuse = { partNo(it) >= 2 } }
        assertIs<SyncOutcome.Failed>(phone.engine.sync(phone.doc, interactive = true))
        relays.byUrl.values.forEach { r -> r.refuse = { false } }
        phone.store.markBroken("test", null)
        val tablet = Device(relays).also { it.ms = phone.ms + 1_000 }
        assertIs<SyncOutcome.Incomplete>(tablet.engine.sync(tablet.doc, interactive = true))
        assertIs<SyncOutcome.Done>(tablet.engine.sync(tablet.doc, rewrite = true))
        // The phone's entries the missing parts held come back from the phone.
        phone.ms = tablet.ms + 1_000
        assertIs<SyncOutcome.Done>(phone.engine.sync(phone.doc))
        val third = Device(relays).also { it.ms = phone.ms + 1_000 }
        assertIs<SyncOutcome.Done>(third.engine.sync(third.doc, interactive = true))
        assertEquals(100, third.doc.applied.size)
    }

    // ── H-01 ────────────────────────────────────────────────────────────────

    /** A keyring the relays lost but for one, and that one down, with only one other answering:
     *  no new keyring over it — and a device holding the key gives it back to the relays that lost it.
     *  (A majority answering without one may make one since the delta audit, MN-08: a keyring counts
     *  as out only once a majority took it.) */
    @Test
    fun aKeyringOnTheOneRelayThatIsDownIsNotReplaced() = runBlocking<Unit> {
        val relays = Relays()
        val phone = Device(relays)
        phone.record("show", "A")
        assertEquals(SyncOutcome.Done(0, true), phone.engine.sync(phone.doc, interactive = true))
        val key = phone.syncKey()

        // Two relays lose everything; the one still holding the keyring is down when a new phone starts,
        // and so is one of the others: one relay of three answers.
        relays.byUrl[relays[1]] = FakeRelay(); relays.byUrl[relays[2]] = FakeRelay()
        relays.down += relays[0]; relays.down += relays[1]
        val fresh = Device(relays)
        val outcome = fresh.engine.sync(fresh.doc, interactive = true)
        assertIs<SyncOutcome.Failed>(outcome)
        val error = assertIs<SyncKeyringNotKnown>(outcome.error)
        assertEquals(1 to 3, error.answered to error.asked)
        assertEquals(1, relays.all().count { it.d() == SyncKeys.DTAG }, "a second keyring went out")

        // The old phone, started again, sends its keyring once more — the relays that lost it have it again.
        relays.down.clear()
        val again = Device(relays).also { it.secrets.put("nostr_sync_key", phone.secrets.get("nostr_sync_key")) }
        again.engine.sync(again.doc)
        relays.down += relays[0]
        assertIs<SyncOutcome.Done>(fresh.engine.sync(fresh.doc, interactive = true))
        assertEquals(key, fresh.syncKey())
    }

    // ── delta audit 2026-10-22 ──────────────────────────────────────────────

    /** MN-08, the real case: one of three relays answers 503 on the very first sync. Two answering
     *  to the end without a keyring is enough to make one; it goes to those two, and to the third
     *  once that is back. */
    @Test
    fun aFirstKeyringIsMadeWithOneRelayOfThreeDown() = runBlocking<Unit> {
        val relays = Relays()
        relays.down += relays[0]
        val phone = Device(relays)
        phone.record("show", "A")
        assertEquals(SyncOutcome.Done(0, true), phone.engine.sync(phone.doc, interactive = true))
        assertEquals(2, relays.all().count { it.d() == SyncKeys.DTAG })
        relays.down.clear()
        phone.ms += 2.minutes.inWholeMilliseconds
        // A new run sends the keyring again: now on all three.
        val again = Device(relays).also { it.ms = phone.ms; it.secrets.put("nostr_sync_key", phone.secrets.get("nostr_sync_key")) }
        assertIs<SyncOutcome.Done>(again.engine.sync(again.doc))
        assertEquals(3, relays.all().count { it.d() == SyncKeys.DTAG })
    }

    /** No background round makes a first keyring — only the listener turning sync on does. */
    @Test
    fun aRoundInTheBackgroundNeverMakesAKeyring() = runBlocking<Unit> {
        val relays = Relays()
        val phone = Device(relays)
        phone.record("show", "A")
        assertIs<SyncOutcome.WaitsForSigner>(phone.engine.sync(phone.doc))
        assertTrue(relays.all().isEmpty())
    }

    // ── M-01 ────────────────────────────────────────────────────────────────

    /** A device holding a renewed key does not go back to the older keyring a relay still shows. */
    @Test
    fun anOlderKeyringIsNeverTakenOverANewerOne() = runBlocking<Unit> {
        val relays = Relays()
        val phone = Device(relays)
        phone.engine.sync(phone.doc, interactive = true)
        phone.ms += 10_000
        phone.engine.renewKey()
        val renewed = phone.syncKey()
        // Every relay now shows only the old keyring.
        val newest = relays.all().filter { it.d() == SyncKeys.DTAG }.maxBy { it.created_at }.id
        relays.byUrl.values.forEach { r -> r.hide = { it.id == newest } }
        phone.ms += 2.minutes.inWholeMilliseconds
        phone.record("show", "B")
        phone.engine.sync(phone.doc)
        assertEquals(renewed, phone.syncKey())
    }

    /** HN-02: a document in two parts read without its first: never "absent", never sent over. */
    @Test
    fun aDocumentWithoutItsFirstPartIsNotSentOver() = runBlocking<Unit> {
        val relays = Relays()
        val tablet = Device(relays)
        val blob = "x".repeat(1_000)
        repeat(90) { tablet.record("k$it", blob) }
        tablet.engine.sync(tablet.doc, interactive = true)
        assertTrue(relays.all().any { it.d() == "hopp/test#2" }, "the test needs two parts")
        val phone = Device(relays)
        phone.engine.sync(phone.doc, interactive = true)
        val before = relays.all().size

        relays.byUrl.values.forEach { r -> r.hide = { it.d() == "hopp/test" } }
        phone.ms += 1_000
        phone.record("mine", "P")
        assertIs<SyncOutcome.Incomplete>(phone.engine.sync(phone.doc))
        relays.byUrl.values.forEach { r -> r.hide = { false } }
        assertEquals(before, relays.all().size, "sent over a document read without its first part")
        phone.engine.sync(phone.doc)
        val third = Device(relays)
        third.engine.sync(third.doc, interactive = true)
        assertEquals((0 until 90).map { "k$it" }.toSet() + "mine", third.doc.applied.keys)
    }

    /** HN-03: a newer revision on two relays, an older one on the third — and only the third answers.
     *  Nothing is sent over the newer one. */
    @Test
    fun anOldRelayAloneIsNotWrittenOverTheNewerMajority() = runBlocking<Unit> {
        val relays = Relays()
        val tablet = Device(relays)
        tablet.record("first", "1")
        tablet.engine.sync(tablet.doc, interactive = true)
        relays.down += relays[2]
        tablet.ms += 2.minutes.inWholeMilliseconds
        tablet.record("second", "2")
        assertEquals(SyncOutcome.Done(0, true), tablet.engine.sync(tablet.doc))
        relays.down.clear()

        relays.down += relays[0]; relays.down += relays[1]
        val phone = Device(relays).also { it.ms = tablet.ms + 1_000 }
        phone.record("mine", "P")
        // Incomplete — or, since delta audit 3 (MN-08/E01), not even started: a new device does not use a
        // keyring only the one relay answering holds. Either way nothing goes out over the newer one.
        val outcome = phone.engine.sync(phone.doc, interactive = true)
        assertTrue(outcome is SyncOutcome.Incomplete || outcome is SyncOutcome.Failed, outcome.toString())
        relays.down.clear()
        phone.engine.sync(phone.doc)
        val third = Device(relays).also { it.ms = phone.ms }
        third.engine.sync(third.doc, interactive = true)
        assertEquals(setOf("first", "second", "mine"), third.doc.applied.keys)
    }

    /** HN-04: away for months, an entry removed elsewhere — and changed here while the removal is applied.
     *  The change stays, in the store and in the app. */
    @Test
    fun aChangeWhileTheRemovalIsAppliedStays() = runBlocking<Unit> {
        val relays = Relays()
        val tablet = Device(relays)
        tablet.record("old show", "S")
        tablet.engine.sync(tablet.doc, interactive = true)
        val phone = Device(relays)
        phone.engine.sync(phone.doc, interactive = true)
        tablet.ms += 1_000
        tablet.record("old show", "S", deleted = true)
        tablet.engine.sync(tablet.doc)
        tablet.ms += 100.days.inWholeMilliseconds
        tablet.record("new show", "N")
        tablet.engine.sync(tablet.doc)

        phone.ms += 100.days.inWholeMilliseconds
        var once = true
        phone.doc.during = { if (once) { once = false; phone.ms += 1_000; phone.record("old show", "changed meanwhile") } }
        phone.engine.sync(phone.doc)
        assertEquals("\"changed meanwhile\"", phone.store.entry("test", "old show")!!.d["v"].toString())
        assertEquals("\"changed meanwhile\"", phone.doc.applied["old show"]!!.d["v"].toString(), "the app holds the change")
        assertTrue(phone.store.unpublished("test"))
    }

    /** HN-05: a removal made offline, the device away for 100 days: the marker is not pruned unsent,
     *  and the show does not come back. */
    @Test
    fun aRemovalNeverSentIsNotPruned() = runBlocking<Unit> {
        val relays = Relays()
        val tablet = Device(relays)
        tablet.record("show", "S")
        tablet.engine.sync(tablet.doc, interactive = true)
        val phone = Device(relays)
        phone.engine.sync(phone.doc, interactive = true)
        relays.byUrl.values.forEach { it.readsFail = true }
        phone.ms += 1_000
        phone.record("show", "S", deleted = true)
        assertIs<SyncOutcome.Failed>(phone.engine.sync(phone.doc))
        relays.byUrl.values.forEach { it.readsFail = false }
        phone.ms += 100.days.inWholeMilliseconds
        phone.engine.sync(phone.doc)
        assertEquals(true, phone.store.entry("test", "show")?.del, "the marker is kept")
        val third = Device(relays).also { it.ms = phone.ms }
        third.engine.sync(third.doc, interactive = true)
        assertTrue("show" !in third.doc.applied.filterValues { !it.del }, "the show came back")
    }

    /** HN-07: a change made exactly while another device's entry is applied: the app ends with the change too. */
    @Test
    fun aChangeWhileApplyingEndsInTheAppToo() = runBlocking<Unit> {
        val relays = Relays()
        val tablet = Device(relays)
        tablet.record("ep", "remote")
        tablet.engine.sync(tablet.doc, interactive = true)
        val phone = Device(relays)
        phone.ms += 1_000
        var once = true
        phone.doc.during = { if (once) { once = false; phone.ms += 1_000; phone.record("ep", "local-new") } }
        phone.engine.sync(phone.doc, interactive = true)
        assertEquals("\"local-new\"", phone.store.entry("test", "ep")!!.d["v"].toString())
        assertEquals("\"local-new\"", phone.doc.applied["ep"]!!.d["v"].toString())
    }

    /** MN-02: a device whose clock is days ahead sent an entry; another device's next version keeps it. */
    @Test
    fun anEntryDatedFarAheadIsCarriedNotDropped() = runBlocking<Unit> {
        val relays = Relays()
        val tablet = Device(relays).also { it.ms += 3.days.inWholeMilliseconds }
        tablet.record("future", "F")
        tablet.engine.sync(tablet.doc, interactive = true)
        val phone = Device(relays)
        phone.record("mine", "P")
        assertEquals(SyncOutcome.Done(0, true), phone.engine.sync(phone.doc, interactive = true))
        assertTrue("future" !in phone.doc.applied, "not taken here")
        val later = Device(relays).also { it.ms += 3.days.inWholeMilliseconds }
        later.engine.sync(later.doc, interactive = true)
        assertEquals(setOf("future", "mine"), later.doc.applied.keys)
    }

    /** MN-03: a key copy from before keyring times were kept, and a read that shows only an older keyring
     *  (one relay down, the others without the newest): the key is not moved. */
    @Test
    fun anOldKeyCopyIsNotMovedByAPartialRead() = runBlocking<Unit> {
        val relays = Relays()
        val phone = Device(relays)
        phone.engine.sync(phone.doc, interactive = true)
        phone.ms += 10_000
        phone.engine.renewKey()
        val renewed = phone.syncKey()
        val held = Json.parseToJsonElement(phone.secrets.get("nostr_sync_key")!!) as JsonObject
        phone.secrets.put("nostr_sync_key", JsonObject(held - "at" - "event").toString())
        val restarted = Device(relays).also { it.ms = phone.ms + 2.minutes.inWholeMilliseconds; it.secrets.put("nostr_sync_key", phone.secrets.get("nostr_sync_key")) }
        val newest = relays.all().filter { it.d() == SyncKeys.DTAG }.maxBy { it.created_at }.id
        relays.byUrl.values.forEach { r -> r.hide = { it.id == newest } }
        relays.down += relays[2]
        restarted.engine.sync(restarted.doc)
        assertEquals(renewed, restarted.syncKey())
    }

    /** MN-04: the keyring sent again to relays that lost it, the first try refused: tried again later. */
    @Test
    fun aRefusedKeyringResendIsTriedAgain() = runBlocking<Unit> {
        val relays = Relays()
        val phone = Device(relays)
        phone.engine.sync(phone.doc, interactive = true)
        relays.byUrl[relays[1]] = FakeRelay(); relays.byUrl[relays[2]] = FakeRelay()
        val again = Device(relays).also { it.secrets.put("nostr_sync_key", phone.secrets.get("nostr_sync_key")) }
        relays.byUrl.values.forEach { it.rejectEvents = true }
        again.engine.sync(again.doc)
        assertEquals(1, relays.all().count { it.d() == SyncKeys.DTAG })
        relays.byUrl.values.forEach { it.rejectEvents = false }
        again.ms += 2.minutes.inWholeMilliseconds
        again.engine.sync(again.doc)
        assertEquals(3, relays.all().count { it.d() == SyncKeys.DTAG })
    }

    /** MN-05: the newest-event read counts only its own answer — another's CLOSED is not an end, its own CLOSED not complete. */
    @Test
    fun aLatestReadCountsOnlyItsOwnEnd() = runBlocking<Unit> {
        val author = KeyPair.generate()
        val list = Event.sign(author, 10002, "", listOf(listOf("r", "wss://a")), nowSeconds)
        fun relay(ownEnd: String) = RelayConnector {
            object : RelaySession {
                val channel = Channel<String>(Channel.UNLIMITED)
                override val incoming: Flow<String> = channel.consumeAsFlow()
                override suspend fun send(message: String) {
                    val arr = Json.parseToJsonElement(message).jsonArray
                    if (arr[0].jsonPrimitive.content != "REQ") return
                    val sub = arr[1].jsonPrimitive.content
                    channel.send("""["CLOSED","not-$sub","error: test"]""")
                    channel.send("""["EVENT","$sub",${Json.encodeToString(Event.serializer(), list)}]""")
                    channel.send("""["$ownEnd","$sub"]""")
                }
                override suspend fun close() { channel.close() }
            }
        }
        val whole = RelayPublisher(relay("EOSE")).latestChecked(10002, author.publicKeyHex, listOf("wss://r"))
        assertEquals(list.id to true, whole.event?.id to whole.complete)
        val closed = RelayPublisher(relay("CLOSED")).latestChecked(10002, author.publicKeyHex, listOf("wss://r"))
        assertEquals(false, closed.complete)
    }

    /** MN-07: a round stopped halfway through applying: the next one applies all of it again. */
    @Test
    fun aRoundCancelledWhileApplyingIsTakenUpAgain() = runBlocking<Unit> {
        val relays = Relays()
        val tablet = Device(relays)
        tablet.record("a", "1"); tablet.record("b", "2")
        tablet.engine.sync(tablet.doc, interactive = true)
        val phone = Device(relays)
        var once = true
        phone.doc.during = { if (once) { once = false; throw kotlinx.coroutines.CancellationException("app closed") } }
        runCatching { phone.engine.sync(phone.doc, interactive = true) }
        assertNull(phone.store.entry("test", "a"))
        phone.engine.sync(phone.doc, interactive = true)
        assertEquals(setOf("a", "b"), phone.doc.applied.keys)
        assertEquals(setOf("a", "b"), phone.store.entries("test").keys)
    }

    // ── delta audit 2 2026-10-22 ────────────────────────────────────────────

    /** HN-02, B01: an older one-part revision still there, a newer one in two parts without its first part:
     *  read in part, nothing sent over it — every entry of the newer one stays. */
    @Test
    fun anOldSmallFirstPartDoesNotHideANewerLargerRevision() = runBlocking<Unit> {
        val relays = Relays()
        val tablet = Device(relays)
        tablet.record("small", "S")
        tablet.engine.sync(tablet.doc, interactive = true)
        tablet.ms += 2.minutes.inWholeMilliseconds
        val blob = "x".repeat(1_000)
        repeat(90) { tablet.record("k$it", blob) }
        tablet.engine.sync(tablet.doc)
        val roots = relays.all().filter { it.d() == "hopp/test" }
        assertTrue(roots.map { it.created_at }.distinct().size == 2 && relays.all().any { it.d() == "hopp/test#2" }, "the test needs both revisions")
        val newRoot = roots.maxBy { it.created_at }.id
        relays.byUrl.values.forEach { r -> r.hide = { it.id == newRoot } }

        val phone = Device(relays).also { it.ms = tablet.ms + 1_000 }
        phone.record("mine", "P")
        assertIs<SyncOutcome.Incomplete>(phone.engine.sync(phone.doc, interactive = true))
        relays.byUrl.values.forEach { r -> r.hide = { false } }
        phone.engine.sync(phone.doc)
        val third = Device(relays).also { it.ms = phone.ms }
        third.engine.sync(third.doc, interactive = true)
        assertEquals((0 until 90).map { "k$it" }.toSet() + "small" + "mine", third.doc.applied.keys)
    }

    /** HN-02: a document that became smaller leaves its old later parts behind — older than the new first
     *  part, so the read is complete and goes on. */
    @Test
    fun aDocumentThatBecameSmallerIsStillReadComplete() = runBlocking<Unit> {
        val relays = Relays()
        val tablet = Device(relays)
        val blob = "x".repeat(1_000)
        repeat(90) { tablet.record("k$it", blob) }
        tablet.engine.sync(tablet.doc, interactive = true)
        assertTrue(relays.all().any { it.d() == "hopp/test#2" })
        tablet.doc.expire = { it.startsWith("k") }
        tablet.ms += 2.minutes.inWholeMilliseconds
        tablet.record("only", "O")
        tablet.engine.sync(tablet.doc)

        val phone = Device(relays).also { it.ms = tablet.ms + 1_000 }
        phone.record("mine", "P")
        assertEquals(SyncOutcome.Done(1, true), phone.engine.sync(phone.doc, interactive = true))
        assertEquals(setOf("only"), phone.doc.applied.keys)
    }

    /** HN-05, X01: a removal made offline and sent 100 days later, while the other device kept syncing:
     *  it counts from when it went out — gone on both, and it stays gone. */
    @Test
    fun aRemovalSentMonthsLaterStillCounts() = runBlocking<Unit> {
        val relays = Relays()
        val tablet = Device(relays)
        tablet.record("show", "S")
        tablet.engine.sync(tablet.doc, interactive = true)
        val phone = Device(relays)
        phone.engine.sync(phone.doc, interactive = true)
        relays.byUrl.values.forEach { it.readsFail = true }
        phone.ms += 1_000
        phone.record("show", "S", deleted = true)
        assertIs<SyncOutcome.Failed>(phone.engine.sync(phone.doc))
        relays.byUrl.values.forEach { it.readsFail = false }
        repeat(10) { tablet.ms += 10.days.inWholeMilliseconds; tablet.engine.sync(tablet.doc) }

        phone.ms = tablet.ms
        phone.engine.sync(phone.doc)
        tablet.ms += 1_000
        tablet.engine.sync(tablet.doc)
        assertEquals(true, tablet.doc.applied["show"]?.del, "the tablet did not take the removal")
        repeat(3) {
            phone.ms += 10.days.inWholeMilliseconds; phone.engine.sync(phone.doc)
            tablet.ms += 10.days.inWholeMilliseconds; tablet.engine.sync(tablet.doc)
        }
        assertEquals(true, phone.store.entry("test", "show")?.del, "the show came back on the phone")
        val third = Device(relays).also { it.ms = tablet.ms }
        third.engine.sync(third.doc, interactive = true)
        assertTrue("show" !in third.doc.applied.filterValues { !it.del }, "the show came back")
    }

    /** HN-07, B05: the listener keeps changing the entry while another device's is applied, more often than
     *  it is put back: the store notes it, and the next round puts the app right. */
    @Test
    fun anAppLeftBehindIsPutRightByTheNextRound() = runBlocking<Unit> {
        val relays = Relays()
        val tablet = Device(relays)
        tablet.record("ep", "remote")
        tablet.engine.sync(tablet.doc, interactive = true)
        val phone = Device(relays)
        var n = 0
        phone.doc.during = { if (n < 10) phone.change("ep", "local-${n++}") }
        phone.engine.sync(phone.doc, interactive = true)
        assertEquals(setOf("ep"), phone.store.unapplied("test").keys, "nothing notes that the app is behind")
        phone.doc.during = {}
        phone.ms += 1_000
        phone.engine.sync(phone.doc)
        assertEquals(phone.store.entry("test", "ep"), phone.doc.applied["ep"])
        assertEquals(emptyMap(), phone.store.unapplied("test"))
    }

    /** MN-07, B06: changed here while another device's older entry is applied, and the round cut off right
     *  after: the next one puts the change back into the app. */
    @Test
    fun aRoundCutOffAfterAnOlderEntryIsPutRight() = runBlocking<Unit> {
        val relays = Relays()
        val tablet = Device(relays)
        tablet.record("ep", "remote")
        tablet.engine.sync(tablet.doc, interactive = true)
        val phone = Device(relays)
        var once = true
        phone.doc.during = { if (once) phone.change("ep", "local-new") }
        phone.doc.after = { if (once) { once = false; throw kotlinx.coroutines.CancellationException("app closed") } }
        runCatching { phone.engine.sync(phone.doc, interactive = true) }
        assertEquals("\"remote\"", phone.doc.applied["ep"]!!.d["v"].toString(), "the test needs the app behind")
        phone.ms += 1_000
        phone.engine.sync(phone.doc)
        assertEquals("\"local-new\"", phone.doc.applied["ep"]!!.d["v"].toString())
        assertEquals(phone.store.entry("test", "ep"), phone.doc.applied["ep"])
    }

    /** MN-02, B03: an entry dated days ahead is kept here — in the store, not the app — and taken in once its time has come. */
    @Test
    fun anEntryDatedAheadIsKeptAndTakenInOnTime() = runBlocking<Unit> {
        val relays = Relays()
        val tablet = Device(relays).also { it.ms += 3.days.inWholeMilliseconds }
        tablet.record("future", "F")
        tablet.engine.sync(tablet.doc, interactive = true)
        val phone = Device(relays)
        phone.engine.sync(phone.doc, interactive = true)
        assertTrue("future" !in phone.doc.applied)
        assertEquals(setOf("future"), phone.store.entries("test").keys)
        phone.ms += 3.days.inWholeMilliseconds
        phone.engine.sync(phone.doc)
        assertEquals(setOf("future"), phone.doc.applied.keys)
        assertEquals(emptyMap(), phone.store.unapplied("test"))
    }

    /** MN-03, B04: a key copy from before keyring times were kept, and every relay answers with an older
     *  keyring only: the key is not moved either. */
    @Test
    fun anOldKeyCopyIsNotMovedByAnOldKeyringEverywhere() = runBlocking<Unit> {
        val relays = Relays()
        val phone = Device(relays)
        phone.engine.sync(phone.doc, interactive = true)
        phone.ms += 10_000
        phone.engine.renewKey()
        val renewed = phone.syncKey()
        val held = Json.parseToJsonElement(phone.secrets.get("nostr_sync_key")!!) as JsonObject
        phone.secrets.put("nostr_sync_key", JsonObject(held - "at" - "event").toString())
        val restarted = Device(relays).also { it.ms = phone.ms + 2.minutes.inWholeMilliseconds; it.secrets.put("nostr_sync_key", phone.secrets.get("nostr_sync_key")) }
        val newest = relays.all().filter { it.d() == SyncKeys.DTAG }.maxBy { it.created_at }.id
        relays.byUrl.values.forEach { r -> r.hide = { it.id == newest } }
        restarted.engine.sync(restarted.doc)
        assertEquals(renewed, restarted.syncKey())
    }

    /** MN-08, B10: a relay keeps the first keyring without saying so, the others refuse it; started again
     *  with that relay down, the device sends the same keyring again — no second key. */
    @Test
    fun aKeyringNoRelayConfirmedIsNotMadeTwice() = runBlocking<Unit> {
        val relays = Relays()
        relays.byUrl[relays[0]]!!.passOnAndReject = true
        relays.byUrl[relays[1]]!!.rejectEvents = true; relays.byUrl[relays[2]]!!.rejectEvents = true
        val phone = Device(relays)
        phone.record("show", "A")
        val first = phone.engine.sync(phone.doc, interactive = true)
        assertIs<SyncNotPublished>(assertIs<SyncOutcome.Failed>(first).error)
        val kept = relays.all().single { it.d() == SyncKeys.DTAG }

        relays.byUrl.values.forEach { it.passOnAndReject = false; it.rejectEvents = false }
        relays.down += relays[0]
        val again = Device(relays, phone.secrets).also { it.ms = phone.ms + 1_000 }
        again.record("show", "A")
        assertIs<SyncOutcome.Done>(again.engine.sync(again.doc, interactive = true))
        assertEquals(setOf(kept.id), relays.all().filter { it.d() == SyncKeys.DTAG }.map { it.id }.toSet())
    }

    /** MN-08, X02: the keyring on one relay of three only: nothing is sealed with it — no round counts as
     *  done — until a majority has it. */
    @Test
    fun nothingIsSealedWithAKeyringOnOneRelayOnly() = runBlocking<Unit> {
        val relays = Relays()
        relays.byUrl[relays[1]]!!.refuse = { it.d() == SyncKeys.DTAG }; relays.byUrl[relays[2]]!!.refuse = { it.d() == SyncKeys.DTAG }
        val phone = Device(relays)
        phone.record("show", "A")
        repeat(2) {
            assertIs<SyncOutcome.Failed>(phone.engine.sync(phone.doc, interactive = true))
            phone.ms += 2.minutes.inWholeMilliseconds
        }
        assertTrue(relays.all().none { it.d() == "hopp/test" }, "a document went out under a key one relay holds")
        relays.byUrl.values.forEach { r -> r.refuse = { false } }
        assertIs<SyncOutcome.Done>(phone.engine.sync(phone.doc))
        assertEquals(3, relays.all().count { it.d() == SyncKeys.DTAG })
        val fresh = Device(relays).also { it.ms = phone.ms }
        fresh.engine.sync(fresh.doc, interactive = true)
        assertEquals(setOf("show"), fresh.doc.applied.keys)
    }

    // ── delta audit 3 2026-10-22 ────────────────────────────────────────────

    /** MN-08, E01: a keyring on one relay of three only, read by another device: nothing sealed with it
     *  there either until a majority has it — the device sends it on itself. */
    @Test
    fun aKeyringReadFromOneRelayIsNotUsedUntilAMajorityHasIt() = runBlocking<Unit> {
        val relays = Relays()
        relays.byUrl[relays[1]]!!.refuse = { it.d() == SyncKeys.DTAG }; relays.byUrl[relays[2]]!!.refuse = { it.d() == SyncKeys.DTAG }
        val first = Device(relays)
        assertIs<SyncOutcome.Failed>(first.engine.sync(first.doc, interactive = true))
        val ring = relays.all().single { it.d() == SyncKeys.DTAG }
        val second = Device(relays).also { it.ms = first.ms + 1_000 }
        second.record("show", "B")
        assertIs<SyncOutcome.Failed>(second.engine.sync(second.doc, interactive = true))
        assertTrue(relays.all().none { it.d() == "hopp/test" }, "a document went out under a keyring one relay holds")
        assertTrue(second.store.unpublished("test"))

        relays.byUrl.values.forEach { r -> r.refuse = { false } }
        second.ms += 2.minutes.inWholeMilliseconds
        assertIs<SyncOutcome.Done>(second.engine.sync(second.doc))
        assertEquals(3, relays.all().count { it.id == ring.id })
        relays.down += relays[0]
        val third = Device(relays).also { it.ms = second.ms }
        third.engine.sync(third.doc, interactive = true)
        assertEquals(setOf("show"), third.doc.applied.keys)
    }

    // ── delta audit 4 2026-10-23 ────────────────────────────────────────────

    /** MN-08, the upgrade: a keyring read from one relay of three and kept as committed by an earlier
     *  beta (309dd16 — no [majority] mark, committed by default). After the update nothing is sealed with
     *  it until a majority has it; the key stays the same, and a fresh device later finds the library. */
    @Test
    fun aKeyringAnEarlierBetaTookFromOneRelayIsConfirmedFirst() = runBlocking<Unit> {
        val relays = Relays()
        relays.byUrl[relays[1]]!!.refuse = { it.d() == SyncKeys.DTAG }; relays.byUrl[relays[2]]!!.refuse = { it.d() == SyncKeys.DTAG }
        val first = Device(relays)
        assertIs<SyncOutcome.Failed>(first.engine.sync(first.doc, interactive = true))
        val ring = relays.all().single { it.d() == SyncKeys.DTAG }
        val second = Device(relays).also { it.ms = first.ms + 1_000 }
        assertIs<SyncOutcome.Failed>(second.engine.sync(second.doc, interactive = true))
        // As 309dd16 kept it: no "committed" (true by default), no "majority".
        val held = Json.parseToJsonElement(second.secrets.get("nostr_sync_key")!!).jsonObject
        assertEquals(ring.id, held["keyring"]!!.jsonPrimitive.content)
        second.secrets.put("nostr_sync_key", JsonObject(held - "committed" - "majority").toString())

        val upgraded = Device(relays, second.secrets).also { it.ms = second.ms + 2.minutes.inWholeMilliseconds }
        upgraded.record("show", "B")
        assertIs<SyncOutcome.Failed>(upgraded.engine.sync(upgraded.doc, interactive = true))
        assertTrue(relays.all().none { it.d() == "hopp/test" }, "a document went out under a keyring one relay holds")
        assertTrue(upgraded.store.unpublished("test"))

        relays.byUrl.values.forEach { r -> r.refuse = { false } }
        upgraded.ms += 2.minutes.inWholeMilliseconds
        assertIs<SyncOutcome.Done>(upgraded.engine.sync(upgraded.doc))
        assertEquals(3, relays.all().count { it.id == ring.id })
        assertEquals(setOf(ring.id), relays.all().filter { it.d() == SyncKeys.DTAG }.map { it.id }.toSet())
        relays.down += relays[0]
        val fresh = Device(relays).also { it.ms = upgraded.ms }
        fresh.engine.sync(fresh.doc, interactive = true)
        assertEquals(setOf("show"), fresh.doc.applied.keys)
    }

    // ── H-02 ────────────────────────────────────────────────────────────────

    /** Reads failing: nothing is sent over what the relays hold. */
    @Test
    fun aFailedReadIsNeverWrittenOver() = runBlocking<Unit> {
        val relays = Relays()
        val tablet = Device(relays)
        tablet.record("theirs", "T")
        tablet.engine.sync(tablet.doc, interactive = true)
        val phone = Device(relays)
        phone.engine.sync(phone.doc, interactive = true)
        val before = relays.all().size

        phone.record("mine", "P")
        relays.byUrl.values.forEach { it.readsFail = true }
        assertIs<SyncOutcome.Failed>(phone.engine.sync(phone.doc))
        relays.byUrl.values.forEach { it.readsFail = false }
        assertEquals(before, relays.all().size, "something was sent while the relays could not be read")
        assertTrue(phone.store.unpublished("test"))
        // Read again: both entries go out together.
        assertEquals(SyncOutcome.Done(0, true), phone.engine.sync(phone.doc))
        val third = Device(relays)
        third.engine.sync(third.doc, interactive = true)
        assertEquals(setOf("theirs", "mine"), third.doc.applied.keys)
    }

    /** A document in two parts read without its second: applied, never sent over. */
    @Test
    fun aDocumentReadInPartIsNotSentOver() = runBlocking<Unit> {
        val relays = Relays()
        val tablet = Device(relays)
        val blob = "x".repeat(1_000)
        repeat(90) { tablet.record("k$it", blob) }
        tablet.engine.sync(tablet.doc, interactive = true)
        assertTrue(relays.all().any { it.d() == "hopp/test#2" }, "the test needs two parts")

        val phone = Device(relays)
        relays.byUrl.values.forEach { r -> r.hide = { it.d() == "hopp/test#2" } }
        phone.record("mine", "P")
        assertIs<SyncOutcome.Incomplete>(phone.engine.sync(phone.doc, interactive = true))
        assertTrue(phone.doc.applied.isNotEmpty() && phone.doc.applied.size < 90, "what arrived is taken")
        relays.byUrl.values.forEach { r -> r.hide = { false } }
        // Whole again: nothing of the second part was lost.
        phone.engine.sync(phone.doc)
        val third = Device(relays)
        third.engine.sync(third.doc, interactive = true)
        assertEquals((0 until 90).map { "k$it" }.toSet() + "mine", third.doc.applied.keys)
    }

    // ── H-03 ────────────────────────────────────────────────────────────────

    /** A change the listener makes while a sync is applying is not written over by the older entry. */
    @Test
    fun aChangeMadeDuringASyncSurvivesIt() = runBlocking<Unit> {
        val relays = Relays()
        val tablet = Device(relays)
        tablet.record("ep", "from the tablet")
        tablet.engine.sync(tablet.doc, interactive = true)
        val phone = Device(relays)
        phone.ms += 1_000
        phone.doc.during = { phone.ms += 1_000; phone.record("ep", "changed here meanwhile") }
        phone.engine.sync(phone.doc, interactive = true)
        assertEquals("\"changed here meanwhile\"", phone.store.entry("test", "ep")!!.d["v"].toString())
        assertTrue(phone.store.unpublished("test"))
    }

    // ── M-02 ────────────────────────────────────────────────────────────────

    /** A device whose clock ran an hour ahead sent last: a change made after it still lands. */
    @Test
    fun aVersionDatedAheadDoesNotHoldBackALaterChange() = runBlocking<Unit> {
        val relays = Relays()
        val tablet = Device(relays)
        tablet.ms += 60.minutes.inWholeMilliseconds
        tablet.record("ep", "old")
        tablet.engine.sync(tablet.doc, interactive = true)
        val phone = Device(relays)
        phone.engine.sync(phone.doc, interactive = true)
        phone.ms += 120.minutes.inWholeMilliseconds / 60
        phone.record("ep", "new")
        assertEquals(SyncOutcome.Done(0, true), phone.engine.sync(phone.doc))
        val third = Device(relays)
        third.engine.sync(third.doc, interactive = true)
        assertEquals("\"new\"", third.doc.applied["ep"]!!.d["v"].toString())
    }

    // ── M-03 ────────────────────────────────────────────────────────────────

    /** Away longer than removals are kept: what was removed meanwhile is not brought back. */
    @Test
    fun aDeviceAwayForMonthsDoesNotBringBackWhatWasRemoved() = runBlocking<Unit> {
        val relays = Relays()
        val tablet = Device(relays)
        tablet.record("old show", "S")
        tablet.engine.sync(tablet.doc, interactive = true)
        val phone = Device(relays)
        phone.engine.sync(phone.doc, interactive = true)
        phone.ms += 1_000
        phone.record("old show", "S", deleted = true)
        phone.engine.sync(phone.doc)
        // 100 days on: the phone's marker is gone, and a later change sends the document without it.
        phone.ms += 100.days.inWholeMilliseconds
        phone.record("new show", "N")
        phone.engine.sync(phone.doc)
        tablet.ms += 100.days.inWholeMilliseconds
        tablet.engine.sync(tablet.doc)
        assertEquals(true, tablet.doc.applied["old show"]?.del, "removed on the tablet too")
        assertNull(tablet.store.entry("test", "old show"))
        val third = Device(relays).also { it.ms += 100.days.inWholeMilliseconds }
        third.engine.sync(third.doc, interactive = true)
        assertEquals(setOf("new show"), third.doc.applied.filterValues { !it.del }.keys)
    }

    // ── M-07 ────────────────────────────────────────────────────────────────

    /** A relay answering with another subscription's events, events not asked for, and another's EOSE. */
    @Test
    fun aRelayAnswerCountsOnlyForItsOwnRequest() = runBlocking<Unit> {
        val author = KeyPair.generate()
        val asked = Event.sign(author, Nip78.KIND, "x", listOf(listOf("d", "hopp/test")), nowSeconds)
        val otherKind = Event.sign(author, 1, "x", emptyList(), nowSeconds)
        val connector = RelayConnector {
            object : RelaySession {
                val channel = Channel<String>(Channel.UNLIMITED)
                override val incoming: Flow<String> = channel.consumeAsFlow()
                override suspend fun send(message: String) {
                    val arr = Json.parseToJsonElement(message).jsonArray
                    if (arr[0].jsonPrimitive.content != "REQ") return
                    val sub = arr[1].jsonPrimitive.content
                    val json = Json.encodeToString(Event.serializer(), asked)
                    channel.send("""["EVENT","not-$sub",$json]""")
                    channel.send("""["EOSE","not-$sub"]""")
                    channel.send("""["EVENT","$sub",${Json.encodeToString(Event.serializer(), otherKind)}]""")
                    channel.send("""["EVENT","$sub",$json]""")
                    channel.send("""["EOSE","$sub"]""")
                }
                override suspend fun close() { channel.close() }
            }
        }
        val filter = Json.parseToJsonElement("""{"kinds":[30078],"authors":["${author.publicKeyHex}"]}""") as JsonObject
        val got = RelayPublisher(connector).queryChecked(listOf(filter), listOf("wss://r"))
        assertEquals(listOf(asked.id), got.events.map { it.id })
        assertEquals(1, got.answered)
    }

    // ── M-04, M-09 (with real feeds) ────────────────────────────────────────

    /** A show restored without downloads fetches nothing; an odd speed from elsewhere is not taken. */
    @Test
    fun aRestoredShowKeepsItsDownloadSettingFromTheStart() = runBlocking<Unit> {
        val relay = FakeRelay()
        val phone = SyncDevice(relay, scope, nsec)
        val s = phone.podcasts.subscribe("https://pod.test/selfhosted.xml") as SubscribeResult.Subscribed
        phone.podcasts.setAutoDownload(s.podcastId, false)
        phone.sync.enable()
        val tablet = SyncDevice(relay, scope, nsec)
        tablet.sync.enable()
        val restored = tablet.row("https://pod.test/selfhosted.xml")
        assertEquals(0L, restored.auto_download)
        assertEquals(0L, tablet.db.episodeQueries.selectByPodcast(restored.id).awaitAsList().count { it.download_state == 1L }.toLong())

        // A speed no Hopp plays, from a device gone wrong: the show keeps its own.
        val doc = SubscriptionsDocument(tablet.db, tablet.podcasts)
        val key = SyncDocs.showKey(restored.podcast_guid, restored.feed_url)
        doc.apply(mapOf(key to SyncEntry(tablet.ms, d = SyncDocs.show(restored.feed_url, null, null, -2f, false))))
        assertNull(tablet.row("https://pod.test/selfhosted.xml").playback_speed)
        val settings = SettingsDocument(tablet.settings)
        settings.apply(mapOf("default_speed" to SyncEntry(tablet.ms, d = SyncDocs.setting("-1")), "skip_back" to SyncEntry(tablet.ms, d = SyncDocs.setting("7"))))
        assertEquals(1f, tablet.settings.current().defaultSpeed)
        assertEquals(10, tablet.settings.current().skipBackSeconds)
    }

    // ── delta audit 5 2026-10-24 ────────────────────────────────────────────

    /** SS-01: "automatic downloads off" from another device, cut off between the setting and dropping
     *  what waits here. The setting already reads "off" when the same entry is applied again — the
     *  clean-up comes then all the same, not only when it was on before. */
    @Test
    fun automaticDownloadsTurnedOffCleanUpAfterAnInterruptedApply() = runBlocking<Unit> {
        val tablet = SyncDevice(FakeRelay(), scope, nsec)
        var drops = 0
        val doc = SettingsDocument(tablet.settings) { if (++drops == 1) throw IllegalStateException("cut off") }
        val off = mapOf("auto_downloads" to SyncEntry(tablet.ms, d = SyncDocs.setting("false")))
        assertTrue(runCatching { doc.apply(off) }.isFailure)
        assertEquals(false, tablet.settings.current().autoDownloads)
        doc.apply(off)
        assertEquals(2, drops)
        // Something else, or "on": nothing to drop.
        doc.apply(mapOf("skip_back" to SyncEntry(tablet.ms, d = SyncDocs.setting("30"))))
        doc.apply(mapOf("auto_downloads" to SyncEntry(tablet.ms, d = SyncDocs.setting("true"))))
        assertEquals(2, drops)
    }

    // ── delta audit 6 2026-10-24 ────────────────────────────────────────────

    /** RP-02, the keyring: a new device whose relays write their answers with spaces in front and the
     *  subscription escaped — valid JSON. Passed over, the keyring looked absent, and the device made a
     *  second key; it takes the listener's one now. */
    @Test
    fun aNewDeviceTakesTheKeyringHoweverTheRelaysWriteIt() = runBlocking<Unit> {
        val relay = FakeRelay()
        val phone = SyncDevice(relay, scope, nsec)
        phone.podcasts.subscribe("https://pod.test/nodesignal.xml")
        assertNull(phone.sync.enable().problem)
        val padded = RelayConnector { url ->
            val inner = relay.connect(url)
            object : RelaySession by inner {
                override val incoming = inner.incoming.map { text ->
                    if (text.startsWith("[\"EVENT\",\"hopp-")) " ".repeat(100) + text.replaceFirst("\"hopp-", "\"\\u0068opp-") else text
                }
            }
        }
        val tablet = SyncDevice(padded, scope, nsec)
        tablet.ms += 1_000
        assertNull(tablet.sync.enable().problem)
        fun ring(d: SyncDevice) = Json.parseToJsonElement(d.secrets.get("nostr_sync_key")!!).jsonObject["keyring"]!!.jsonPrimitive.content
        assertEquals(ring(phone), ring(tablet))
        assertEquals(setOf("https://pod.test/nodesignal.xml"), tablet.urls())
    }

    /** SS-01, the restore: settings first is not enough when their read fails. The shows waited for
     *  nothing then, came under the fresh default and queued their newest episode although automatic
     *  downloads are off elsewhere. They wait for the settings now — read once, both come. */
    @Test
    fun aRestoreWhoseSettingsCannotBeReadWaitsWithTheShows() = runBlocking<Unit> {
        val relay = FakeRelay()
        val phone = SyncDevice(relay, scope, nsec)
        phone.settings.update { it.copy(autoDownloads = false) }
        phone.podcasts.subscribe("https://pod.test/nodesignal.xml")
        assertNull(phone.sync.enable().problem)

        val settingsTag = SyncCodec.dTag(SyncDocs.SETTINGS)
        relay.failReads = { f -> f["#d"]?.toString()?.contains(settingsTag) == true }
        val tablet = SyncDevice(relay, scope, nsec)
        tablet.ms += 1_000
        assertTrue(tablet.sync.enable().problem != null)
        assertEquals(true, tablet.settings.current().autoDownloads)
        assertEquals(emptySet(), tablet.urls())

        relay.failReads = { false }
        tablet.ms += 60_000
        assertNull(tablet.sync.syncNow().problem)
        assertEquals(false, tablet.settings.current().autoDownloads)
        assertEquals(setOf("https://pod.test/nodesignal.xml"), tablet.urls())
        val row = tablet.row("https://pod.test/nodesignal.xml")
        assertEquals(0, tablet.db.episodeQueries.selectByPodcast(row.id).awaitAsList().count { it.download_state != 0L })
    }

    // ── M-05 ────────────────────────────────────────────────────────────────

    /** Two episodes with one sort key, put in in another order on each device: the same order on both. */
    @Test
    fun aTieInUpNextEndsInOneOrderEverywhere() = runBlocking<Unit> {
        val relay = FakeRelay()
        val a = SyncDevice(relay, scope, nsec)
        val b = SyncDevice(relay, scope, nsec)
        for (d in listOf(a, b)) d.podcasts.subscribe("https://pod.test/nodesignal.xml")
        suspend fun ids(d: SyncDevice) = d.db.episodeQueries.selectByPodcast(d.row("https://pod.test/nodesignal.xml").id).awaitAsList().take(3).map { it.id }
        ids(a).forEach { a.queue.applySynced(it, 7) }
        ids(b).reversed().forEach { b.queue.applySynced(it, 7) }
        assertEquals(a.queueGuids(), b.queueGuids())
        assertEquals(3, a.queueGuids().size)
        // Broken by what sync names the episodes by — not by this device's ids, which differ between devices.
        assertEquals(a.queueGuids().sorted(), a.queueGuids())
    }
}
