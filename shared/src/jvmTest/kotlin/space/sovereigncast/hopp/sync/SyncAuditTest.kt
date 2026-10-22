package space.sovereigncast.hopp.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
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
        override suspend fun apply(entries: Map<String, SyncEntry>): Set<String> { during(); applied += entries; return entries.keys }
        override suspend fun snapshot(): Map<String, SyncEntry> = emptyMap()
    }

    private inner class Device(val relays: RelayConnector) {
        var ms = nowSeconds * 1000
        val secrets = MemorySecretStore()
        val identity = IdentityRepository(secrets, RelayPublisher(relays), { ms / 1000 }).also { runBlocking { it.importKey(nsec) } }
        val db = HoppDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HoppDatabase.Schema.create(it) })
        val store = SyncStore(db) { ms }
        val keys = SyncKeys(identity, RelayPublisher(relays), secrets) { ms }
        val engine = SyncEngine(store, SyncRelays(RelayPublisher(relays)) { ms / 1000 }, identity, keys) { ms }
        val doc = Doc()
        fun record(key: String, v: String, deleted: Boolean = false) = store.record("test", key, JsonObject(mapOf("v" to JsonPrimitive(v))), deleted)
        suspend fun syncKey() = keys.signer(interactive = false, create = false)?.pubkey
    }

    private fun Event.d() = tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)

    // ── H-01 ────────────────────────────────────────────────────────────────

    /** A keyring the relays lost but for one, and that one down: no new keyring over it —
     *  and a device holding the key gives it back to the relays that lost it. */
    @Test
    fun aKeyringOnTheOneRelayThatIsDownIsNotReplaced() = runBlocking<Unit> {
        val relays = Relays()
        val phone = Device(relays)
        phone.record("show", "A")
        assertEquals(SyncOutcome.Done(0, true), phone.engine.sync(phone.doc, interactive = true))
        val key = phone.syncKey()

        // Two relays lose everything; the one still holding the keyring is down when a new phone starts.
        relays.byUrl[relays[1]] = FakeRelay(); relays.byUrl[relays[2]] = FakeRelay()
        relays.down += relays[0]
        val fresh = Device(relays)
        val outcome = fresh.engine.sync(fresh.doc, interactive = true)
        assertIs<SyncOutcome.Failed>(outcome)
        assertIs<SyncKeyringNotKnown>(outcome.error)
        assertEquals(1, relays.all().count { it.d() == SyncKeys.DTAG }, "a second keyring went out")

        // The old phone, started again, sends its keyring once more — the relays that lost it have it again.
        relays.down.clear()
        val again = Device(relays).also { it.secrets.put("nostr_sync_key", phone.secrets.get("nostr_sync_key")) }
        again.engine.sync(again.doc)
        relays.down += relays[0]
        assertIs<SyncOutcome.Done>(fresh.engine.sync(fresh.doc, interactive = true))
        assertEquals(key, fresh.syncKey())
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
        assertEquals(0L, tablet.db.episodeQueries.selectByPodcast(restored.id).executeAsList().count { it.download_state == 1L }.toLong())

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

    // ── M-05 ────────────────────────────────────────────────────────────────

    /** Two episodes with one sort key, put in in another order on each device: the same order on both. */
    @Test
    fun aTieInUpNextEndsInOneOrderEverywhere() = runBlocking<Unit> {
        val relay = FakeRelay()
        val a = SyncDevice(relay, scope, nsec)
        val b = SyncDevice(relay, scope, nsec)
        for (d in listOf(a, b)) d.podcasts.subscribe("https://pod.test/nodesignal.xml")
        fun ids(d: SyncDevice) = d.db.episodeQueries.selectByPodcast(d.row("https://pod.test/nodesignal.xml").id).executeAsList().take(3).map { it.id }
        ids(a).forEach { a.queue.applySynced(it, 7) }
        ids(b).reversed().forEach { b.queue.applySynced(it, 7) }
        assertEquals(a.queueGuids(), b.queueGuids())
        assertEquals(3, a.queueGuids().size)
        // Broken by what sync names the episodes by — not by this device's ids, which differ between devices.
        assertEquals(a.queueGuids().sorted(), a.queueGuids())
    }
}
