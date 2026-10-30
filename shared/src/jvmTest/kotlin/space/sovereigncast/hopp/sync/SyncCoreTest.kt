package space.sovereigncast.hopp.sync

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.nostr.LocalSigner
import space.sovereigncast.hopp.nostr.Nip78
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.nostr.UnsignedEvent
import space.sovereigncast.hopp.platform.MemorySecretStore
import space.sovereigncast.hopp.testDatabase
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
        assertEquals(entries, SyncCodec.join(parts.associate { it.part to SyncCodec.decode(SyncCodec.encode(it)) }))
        assertEquals(listOf("hopp/progress", "hopp/progress#2"), SyncCodec.dTags("progress").take(2))
    }

    /** Maintainer 2026-10-30: nos.lol refused a progress part, "invalid: event too large: 87839". */
    @Test
    fun theFullestPartStaysUnderTheRelaysEventLimit() = runBlocking<Unit> {
        val signer = LocalSigner(KeyPair.generate())
        val fullest = "x".repeat(SyncCodec.MAX_PART_BYTES)
        val event = signer.sign(UnsignedEvent(Nip78.KIND, signer.encryptToSelf(fullest), listOf(listOf("d", SyncCodec.dTag("subscriptions", SyncCodec.MAX_PARTS))), 1_790_000_000))
        val size = Json.encodeToString(Event.serializer(), event).encodeToByteArray().size
        assertTrue(size <= SyncCodec.MAX_EVENT_BYTES, "an event of $size bytes")
    }

    @Test
    fun partsOfAnotherRevisionAreNotMixedIn() {
        val old = SyncCodec.split(mapOf("gone" to SyncEntry(1)), "old").single().copy(part = 2, parts = 2)
        val now = SyncCodec.split(mapOf("here" to SyncEntry(2)), "new").single()
        assertEquals(setOf("here"), SyncCodec.join(mapOf(1 to now, 2 to old))!!.keys)
    }

    /** Security audit 2026-10-22, H-02/M-04: only a whole revision is a document. */
    @Test
    fun aDocumentIsWholeOnlyWithEveryPartOfItsRevision() {
        val entries = (1..3000).associate { "k$it" to SyncEntry(1_760_000_000_000L + it, d = data("pos" to "$it")) }
        val parts = SyncCodec.split(entries, "r").associateBy { it.part }
        assertTrue(parts.size >= 2)
        assertNull(SyncCodec.join(parts - 2), "a part missing")
        assertNull(SyncCodec.join(parts + (2 to parts[2]!!.copy(rev = "other"))), "a part of another revision")
        assertNull(SyncCodec.join(parts + (2 to parts[1]!!)), "a part under another part's address")
        assertNull(SyncCodec.join(mapOf(1 to parts[1]!!.copy(parts = 0))), "a count out of range")
        assertNull(SyncCodec.join(mapOf(1 to parts[1]!!.copy(parts = SyncCodec.MAX_PARTS + 1))))
        // What arrived of it may still be applied — never sent over.
        assertEquals((parts - 2).values.flatMap { it.entries.keys }.toSet(), SyncCodec.joinWhatArrived(parts - 2).keys)
    }

    /** M-04: nothing is cut silently — too much is an error, not a smaller document. */
    @Test
    fun aDocumentTooLargeIsNotCut() {
        val big = data("blob" to "x".repeat(SyncCodec.MAX_PART_BYTES))
        assertFailsWith<SyncTooLarge> { SyncCodec.split(mapOf("one" to SyncEntry(1, d = big)), "r", "favourites") }
        val many = (1..60_000).associate { "favourite-of-a-long-listening-life-$it" to SyncEntry(it.toLong(), d = data("show" to "guid:$it", "guid" to "episode-$it")) }
        assertFailsWith<SyncTooLarge> { SyncCodec.split(many, "r", "progress") }
    }

    @Test
    fun aDocumentOfANewerHoppIsLeftAlone() {
        assertFailsWith<SyncNewerVersion> { SyncCodec.decode("""{"v":2,"rev":"x","entries":{}}""") }
        // Fields a newer version adds within version 1 are read past.
        assertEquals(setOf("a"), SyncCodec.decode("""{"v":1,"rev":"x","later":true,"entries":{"a":{"t":1,"more":1}}}""").entries.keys)
    }

    // ── store ───────────────────────────────────────────────────────────────

    private fun db() = testDatabase()

    @Test
    fun aChangeHereIsLaterThanWhatItReplacesEvenWithAClockBehind() = runTest {
        var now = 1_000L
        val store = SyncStore(db()) { now }
        store.putIfNewer("subscriptions", mapOf("show" to SyncEntry(5_000, d = data("by" to "the other device, clock ahead"))))
        val mine = store.record("subscriptions", "show", data(), deleted = true)
        assertEquals(5_001, mine.t)
        assertTrue(store.entry("subscriptions", "show")!!.del)
        now = 10_000
        assertEquals(10_000, store.record("subscriptions", "other", data()).t)
    }

    @Test
    fun oldRemovalMarkersGoAndWhatIsUnsentIsKnownPerEntry() = runTest {
        var now = 1_000L
        val store = SyncStore(db()) { now }
        store.putIfNewer("queue", mapOf("old" to SyncEntry(10, del = true), "new" to SyncEntry(500, del = true), "live" to SyncEntry(10)))
        store.pruneRemoved("queue", 100)
        assertEquals(setOf("new", "live"), store.entries("queue").keys)
        assertFalse(store.unpublished("queue"), "from the relays: nothing to send")
        // Audit 2026-10-22, M-02: an entry dated far ahead does not hide a later change to another.
        store.putIfNewer("queue", mapOf("ahead" to SyncEntry(9_000_000_000_000)))
        val change = store.record("queue", "live", data("v" to "here"))
        assertTrue(store.unpublished("queue"))
        store.markSent("queue", mapOf("live" to change))
        assertFalse(store.unpublished("queue"))
        // Sent an older version of it: the change made meanwhile stays to send.
        now = 2_000
        val later = store.record("queue", "live", data("v" to "again"))
        store.markSent("queue", mapOf("live" to change))
        assertTrue(store.unpublished("queue"))
        store.markSent("queue", mapOf("live" to later))
        store.clear()
        assertTrue(store.entries("queue").isEmpty())
    }

    /** Audit 2026-10-22, H-03: a change made while a sync was on its way is not written over. */
    @Test
    fun anOlderEntryFromTheRelaysNeverReplacesANewerOneHere() = runTest {
        var now = 1_000L
        val store = SyncStore(db()) { now }
        now = 5_000
        store.record("progress", "ep", data("pos" to "here"))
        assertTrue(store.putIfNewer("progress", mapOf("ep" to SyncEntry(3_000, d = data("pos" to "older")))).isEmpty())
        assertEquals("\"here\"", store.entry("progress", "ep")!!.d["pos"].toString())
        assertTrue(store.stillNewer("progress", mapOf("ep" to SyncEntry(3_000))).isEmpty())
        assertEquals(setOf("ep"), store.putIfNewer("progress", mapOf("ep" to SyncEntry(6_000, d = data("pos" to "newer")))))
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
        val keys = SyncKeys(identity, RelayPublisher(relay), secrets) { ms }
        val engine = SyncEngine(store, SyncRelays(RelayPublisher(relay)) { ms / 1000 }, identity, keys) { ms }
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
        // The first keyring only with the listener there (audit 2026-10-22, H-01).
        assertEquals(SyncOutcome.Done(0, true), phone.engine.sync(docPhone, interactive = true))
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
        // The listener signed the keyring only; the document is the sync key's, the same on both devices.
        val (ring, docs) = events.partition { it.pubkey == keys.publicKeyHex }
        assertEquals(listOf(listOf("d", SyncKeys.DTAG)), ring.single().tags)
        val syncKey = phone.keys.signer(interactive = false)!!.pubkey
        assertEquals(syncKey, tablet.keys.signer(interactive = false)!!.pubkey)
        assertTrue(docs.isNotEmpty() && docs.all { e -> e.tags.any { it == listOf("d", "hopp/test") } && e.pubkey == syncKey })

        // Unsubscribed on the tablet: the phone follows, and does not bring it back.
        tablet.ms += 2_000
        tablet.store.record("test", "show-a", data(), deleted = true)
        tablet.engine.sync(docTablet)
        phone.engine.sync(docPhone)
        assertTrue(docPhone.applied.getValue("show-a").del)
        assertEquals(SyncOutcome.Done(0, false), tablet.engine.sync(docTablet))
    }

    /**
     * Maintainer 2026-10-30: "Incomplete", every relay answered, four changes waiting that never went out.
     * A version that only partly went out (a relay refused one part) leaves a revision with a gap on the
     * relays; with a majority read to its end, that gap never fills — a new, whole version goes out over
     * it, with every entry that arrived, instead of waiting for ever.
     */
    @Test
    fun aVersionThatPartlyWentOutIsWrittenOverOnceTheRelaysHaveAnswered() = runBlocking<Unit> {
        val nsec = space.sovereigncast.hopp.nostr.Bech32.encode("nsec", KeyPair.generate().secretKey)
        val phone = Device(nsec)
        val doc = MemoryDocument()
        (1..1500).forEach { phone.store.record("test", "podcast-guid-$it|episode-guid-$it", data("pos" to "${it * 1000}", "played" to "false")) }
        assertEquals(SyncOutcome.Done(0, true), phone.engine.sync(doc, interactive = true))
        // A change; the third part of its version is refused everywhere: parts 1 and 2 are new, part 3 is the old one.
        phone.ms += 2_000
        phone.store.record("test", "podcast-guid-1|episode-guid-1", data("pos" to "5", "played" to "true"))
        relay.refuse = { e -> e.tags.any { it == listOf("d", "hopp/test#3") } }
        assertIs<SyncOutcome.Failed>(phone.engine.sync(doc))
        relay.refuse = { false }
        // Every relay answers; the version on them has a gap that no relay will ever fill.
        phone.ms += 2_000
        assertEquals(SyncOutcome.Done(0, true), phone.engine.sync(doc))
        assertFalse(phone.store.unpublished("test"))
        // Another device reads it whole, the change included.
        val tablet = Device(nsec)
        val other = MemoryDocument()
        tablet.ms = phone.ms + 2_000
        assertEquals(SyncOutcome.Done(1500, false), tablet.engine.sync(other, interactive = true))
        assertEquals("true", other.applied.getValue("podcast-guid-1|episode-guid-1").d?.get("played")?.let { (it as JsonPrimitive).content })
    }

    /** The same gap left by a Hopp that did not keep which version failed: the missing part is there in an
     *  older version only — a relay that had taken the newer one would hold it instead — so it is written over too. */
    @Test
    fun aGapOfOlderPartsOnlyIsWrittenOverWithoutKnowingWhoLeftIt() = runBlocking<Unit> {
        val nsec = space.sovereigncast.hopp.nostr.Bech32.encode("nsec", KeyPair.generate().secretKey)
        val phone = Device(nsec)
        val doc = MemoryDocument()
        (1..1500).forEach { phone.store.record("test", "podcast-guid-$it|episode-guid-$it", data("pos" to "${it * 1000}")) }
        phone.engine.sync(doc, interactive = true)
        phone.ms += 2_000
        phone.store.record("test", "podcast-guid-2|episode-guid-2", data("pos" to "7"))
        relay.refuse = { e -> e.tags.any { it == listOf("d", "hopp/test#3") } }
        assertIs<SyncOutcome.Failed>(phone.engine.sync(doc))
        relay.refuse = { false }
        // Another device, which never sent anything, finds the gap: older parts only — it writes over it.
        val tablet = Device(nsec)
        tablet.ms = phone.ms + 2_000
        val other = MemoryDocument()
        assertEquals(SyncOutcome.Done(1500, true), tablet.engine.sync(other, interactive = true))
        val third = Device(nsec)
        third.ms = tablet.ms + 2_000
        assertIs<SyncOutcome.Done>(third.engine.sync(MemoryDocument(), interactive = true))
    }

    /**
     * Maintainer 2026-10-30, the second time: a larger version cut off after its second part — later parts
     * nowhere, and a Hopp that did not keep which version it left. Another device waits at first (a relay
     * may hold a part back for a moment, HN-02), and writes over the gap once every relay has shown it
     * for [SyncEngine.STUCK_FOR].
     */
    @Test
    fun aGapEveryRelayShowsForTenMinutesIsWrittenOver() = runBlocking<Unit> {
        val nsec = space.sovereigncast.hopp.nostr.Bech32.encode("nsec", KeyPair.generate().secretKey)
        val phone = Device(nsec)
        val doc = MemoryDocument()
        (1..700).forEach { phone.store.record("test", "podcast-guid-$it|episode-guid-$it", data("pos" to "${it * 1000}")) }
        phone.engine.sync(doc, interactive = true)
        // Larger now; its parts after the second never arrive anywhere.
        phone.ms += 2_000
        (701..1500).forEach { phone.store.record("test", "podcast-guid-$it|episode-guid-$it", data("pos" to "${it * 1000}")) }
        relay.refuse = { e -> e.tags.any { t -> t.size >= 2 && t[0] == "d" && t[1].substringAfter('#', "1").toInt() >= 3 } }
        assertIs<SyncOutcome.Failed>(phone.engine.sync(doc))
        relay.refuse = { false }
        // As a Hopp before 0.3.2 left it: nothing kept of the version it cut off.
        phone.store.markBroken("test", null)

        val tablet = Device(nsec)
        tablet.ms = phone.ms + 2_000
        val other = MemoryDocument()
        assertEquals(SyncOutcome.Incomplete(other.applied.size, "test").doc, (tablet.engine.sync(other, interactive = true) as SyncOutcome.Incomplete).doc)
        tablet.ms += 5 * 60_000
        assertIs<SyncOutcome.Incomplete>(tablet.engine.sync(other))
        tablet.ms += 6 * 60_000
        assertIs<SyncOutcome.Done>(tablet.engine.sync(other))
        // Whole again: the phone reads it complete, and sends what it alone still had.
        phone.ms = tablet.ms + 2_000
        assertIs<SyncOutcome.Done>(phone.engine.sync(doc))
        val third = Device(nsec)
        third.ms = phone.ms + 2_000
        val all = MemoryDocument()
        assertIs<SyncOutcome.Done>(third.engine.sync(all, interactive = true))
        assertEquals(1500, all.applied.size)
    }

    @Test
    fun aDocumentPostedUnderAnotherKeyIsIgnored() = runBlocking<Unit> {
        val me = Device(space.sovereigncast.hopp.nostr.Bech32.encode("nsec", KeyPair.generate().secretKey))
        val stranger = LocalSigner(KeyPair.generate())
        val forged = stranger.sign(UnsignedEvent(Nip78.KIND, stranger.encryptToSelf("""{"v":1,"rev":"x","entries":{"evil":{"t":1}}}"""), listOf(listOf("d", "hopp/test")), nowSeconds))
        relay.publish(forged)
        val doc = MemoryDocument()
        assertEquals(SyncOutcome.Done(0, false), me.engine.sync(doc, interactive = true))
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
        assertIs<SyncOutcome.Failed>(me.engine.sync(MemoryDocument(), interactive = true))
        assertTrue(me.store.unpublished("test"))
        relay.rejectEvents = false
        assertEquals(SyncOutcome.Done(0, true), me.engine.sync(MemoryDocument(), interactive = true))
        assertFalse(me.store.unpublished("test"))
    }
}
