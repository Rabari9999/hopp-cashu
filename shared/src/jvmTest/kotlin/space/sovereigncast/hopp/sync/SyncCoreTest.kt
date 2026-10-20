package space.sovereigncast.hopp.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.nostr.LocalSigner
import space.sovereigncast.hopp.nostr.Nip78
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.nostr.UnsignedEvent
import space.sovereigncast.hopp.platform.MemorySecretStore
import space.sovereigncast.hopp.value.FakeRelay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun data(vararg pairs: Pair<String, String>): JsonObject = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

/** A document that keeps what it was given, as subscriptions or progress will. */
private class MemoryDocument(override val name: String = "test") : SyncedDocument {
    val applied = mutableMapOf<String, SyncEntry>()
    override suspend fun apply(entries: Map<String, SyncEntry>): Set<String> { applied += entries; return entries.keys }
    override suspend fun snapshot(): Map<String, SyncEntry> = emptyMap()
}

/** Sync's core, ADR 0021 stage 5 (package 2 of docs/tasks/nostr-sync.md). */
class SyncCoreTest {
    // ── merge ───────────────────────────────────────────────────────────────

    @Test
    fun theNewerEntryWinsPerEntryNotPerDocument() {
        val mine = mapOf("a" to SyncEntry(200, d = data("v" to "mine")), "b" to SyncEntry(100, d = data("v" to "mine")))
        val theirs = mapOf("a" to SyncEntry(150, d = data("v" to "theirs")), "b" to SyncEntry(300, d = data("v" to "theirs")), "c" to SyncEntry(50))
        val m = SyncMerge.merge(mine, theirs)
        assertEquals(setOf("b", "c"), m.fromThem.keys)
        assertTrue(m.theyLack, "a is newer here")
        assertEquals("mine", (m.all["a"]!!.d["v"] as JsonPrimitive).content)
        assertEquals("theirs", (m.all["b"]!!.d["v"] as JsonPrimitive).content)
    }

    @Test
    fun aRemovalStaysRemoved() {
        val m = SyncMerge.merge(mapOf("show" to SyncEntry(100)), mapOf("show" to SyncEntry(200, del = true)))
        assertTrue(m.all.getValue("show").del)
        assertFalse(m.theyLack)
    }

    @Test
    fun twoDevicesEndAlikeWhateverTheOrder() {
        val a = mapOf("x" to SyncEntry(100, d = data("v" to "1")), "y" to SyncEntry(100, del = true))
        val b = mapOf("x" to SyncEntry(100, d = data("v" to "2")), "y" to SyncEntry(100, d = data("v" to "3")))
        assertEquals(SyncMerge.merge(a, b).all, SyncMerge.merge(b, a).all)
        // Merged once, nothing is left to send or take.
        val both = SyncMerge.merge(a, b).all
        assertFalse(SyncMerge.merge(both, both).theyLack)
        assertTrue(SyncMerge.merge(both, both).fromThem.isEmpty())
    }

    // ── format ──────────────────────────────────────────────────────────────

    @Test
    fun aLongDocumentGoesInPartsAndComesBackWhole() {
        val entries = (1..3000).associate { "podcast-guid-$it|episode-guid-$it" to SyncEntry(1_760_000_000_000L + it, d = data("pos" to "${it * 1000}", "played" to "false")) }
        val parts = SyncCodec.split(entries, "r1")
        assertTrue(parts.size > 1)
        parts.forEach { assertTrue(SyncCodec.encode(it).encodeToByteArray().size <= SyncCodec.MAX_PART_BYTES, "a part over NIP-44's limit") }
        assertEquals(entries, SyncCodec.join(parts.map { SyncCodec.decode(SyncCodec.encode(it)) }))
        assertEquals(listOf("hopp/progress", "hopp/progress#2"), SyncCodec.dTags("progress").take(2))
    }

    @Test
    fun partsOfAnotherRevisionAreNotMixedIn() {
        val old = SyncCodec.split(mapOf("gone" to SyncEntry(1)), "old").single().copy(part = 2, parts = 2)
        val now = SyncCodec.split(mapOf("here" to SyncEntry(2)), "new").single()
        assertEquals(setOf("here"), SyncCodec.join(listOf(now, old)).keys)
    }

    @Test
    fun aDocumentOfANewerHoppIsLeftAlone() {
        assertFailsWith<SyncNewerVersion> { SyncCodec.decode("""{"v":2,"rev":"x","entries":{}}""") }
        // Fields a newer version adds within version 1 are read past.
        assertEquals(setOf("a"), SyncCodec.decode("""{"v":1,"rev":"x","later":true,"entries":{"a":{"t":1,"more":1}}}""").entries.keys)
    }

    // ── store ───────────────────────────────────────────────────────────────

    private fun db() = HoppDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HoppDatabase.Schema.create(it) })

    @Test
    fun aChangeHereIsLaterThanWhatItReplacesEvenWithAClockBehind() {
        var now = 1_000L
        val store = SyncStore(db()) { now }
        store.putAll("subscriptions", mapOf("show" to SyncEntry(5_000, d = data("by" to "the other device, clock ahead"))))
        val mine = store.record("subscriptions", "show", data(), deleted = true)
        assertEquals(5_001, mine.t)
        assertTrue(store.entry("subscriptions", "show")!!.del)
        now = 10_000
        assertEquals(10_000, store.record("subscriptions", "other", data()).t)
    }

    @Test
    fun oldRemovalMarkersGoAndThePublishedMarkIsKept() {
        val store = SyncStore(db()) { 0 }
        store.putAll("queue", mapOf("old" to SyncEntry(10, del = true), "new" to SyncEntry(500, del = true), "live" to SyncEntry(10)))
        store.pruneRemoved("queue", 100)
        assertEquals(setOf("new", "live"), store.entries("queue").keys)
        assertTrue(store.unpublished("queue"))
        store.markPublished("queue", 500)
        assertFalse(store.unpublished("queue"))
        store.clear()
        assertTrue(store.entries("queue").isEmpty())
        assertNull(store.publishedUpTo("queue"))
    }

    // ── engine, against a relay ─────────────────────────────────────────────

    private val relay = FakeRelay()
    private val nowSeconds = 1_790_000_000L

    /** A device: its own database and secret store, the same key. */
    private inner class Device(nsec: String?) {
        val secrets = MemorySecretStore()
        val identity = IdentityRepository(secrets, RelayPublisher(relay), { nowSeconds })
        var ms = nowSeconds * 1000
        val store = SyncStore(db()) { ms }
        // Events by the device's own clock: two in the same second would be a tie on the relays.
        val engine = SyncEngine(store, SyncRelays(RelayPublisher(relay)) { ms / 1000 }, identity) { ms }
        init { if (nsec != null) runBlocking { identity.importKey(nsec) } }
    }

    @Test
    fun twoDevicesWithOneKeyKeepEachOthersEntries() = runBlocking<Unit> {
        val keys = KeyPair.generate()
        val nsec = space.sovereigncast.hopp.nostr.Bech32.encode("nsec", keys.secretKey)
        val phone = Device(nsec)
        val tablet = Device(nsec)
        val docPhone = MemoryDocument(); val docTablet = MemoryDocument()

        phone.store.record("test", "show-a", data("title" to "A"))
        assertEquals(SyncOutcome.Done(0, true), phone.engine.sync(docPhone))
        // The tablet adds its own before it ever synced: neither is lost.
        tablet.ms += 2_000
        tablet.store.record("test", "show-b", data("title" to "B"))
        assertEquals(SyncOutcome.Done(1, true), tablet.engine.sync(docTablet))
        assertEquals(setOf("show-a"), docTablet.applied.keys)
        assertEquals(SyncOutcome.Done(1, false), phone.engine.sync(docPhone))
        assertEquals(setOf("show-b"), docPhone.applied.keys)
        assertEquals(phone.store.entries("test"), tablet.store.entries("test"))

        // The content is sealed: the relay holds no title, and the d tag names the document.
        val events = RelayPublisher(relay).query(listOf(kotlinx.serialization.json.Json.parseToJsonElement("""{"kinds":[30078]}""") as JsonObject), listOf("wss://r"))
        assertTrue(events.isNotEmpty() && events.none { "show-a" in it.content || "\"A\"" in it.content })
        assertTrue(events.all { e -> e.tags.any { it == listOf("d", "hopp/test") } && e.pubkey == keys.publicKeyHex })

        // Unsubscribed on the tablet: the phone follows, and does not bring it back.
        tablet.ms += 2_000
        tablet.store.record("test", "show-a", data(), deleted = true)
        tablet.engine.sync(docTablet)
        phone.engine.sync(docPhone)
        assertTrue(docPhone.applied.getValue("show-a").del)
        assertEquals(SyncOutcome.Done(0, false), tablet.engine.sync(docTablet))
    }

    @Test
    fun aDocumentPostedUnderAnotherKeyIsIgnored() = runBlocking<Unit> {
        val me = Device(space.sovereigncast.hopp.nostr.Bech32.encode("nsec", KeyPair.generate().secretKey))
        val stranger = LocalSigner(KeyPair.generate())
        val forged = stranger.sign(UnsignedEvent(Nip78.KIND, stranger.encryptToSelf("""{"v":1,"rev":"x","entries":{"evil":{"t":1}}}"""), listOf(listOf("d", "hopp/test")), nowSeconds))
        relay.publish(forged)
        val doc = MemoryDocument()
        assertEquals(SyncOutcome.Done(0, false), me.engine.sync(doc))
        assertTrue(doc.applied.isEmpty())
    }

    @Test
    fun withoutAnIdentityNothingHappens() = runBlocking<Unit> {
        assertIs<SyncOutcome.NoIdentity>(Device(null).engine.sync(MemoryDocument()))
    }

    @Test
    fun whenNoRelayTakesItItStaysUnpublished() = runBlocking<Unit> {
        val me = Device(space.sovereigncast.hopp.nostr.Bech32.encode("nsec", KeyPair.generate().secretKey))
        me.store.record("test", "x", data())
        relay.rejectEvents = true
        assertIs<SyncOutcome.Failed>(me.engine.sync(MemoryDocument()))
        assertTrue(me.store.unpublished("test"))
        relay.rejectEvents = false
        assertEquals(SyncOutcome.Done(0, true), me.engine.sync(MemoryDocument()))
        assertFalse(me.store.unpublished("test"))
    }
}
