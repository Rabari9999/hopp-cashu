package space.sovereigncast.hopp.identity

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.sovereigncast.hopp.crypto.Sha256
import space.sovereigncast.hopp.crypto.toHex
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.nostr.LocalSigner
import space.sovereigncast.hopp.nostr.Nip49
import space.sovereigncast.hopp.nostr.Nip98
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.nostr.npubToHex
import space.sovereigncast.hopp.platform.MemorySecretStore
import space.sovereigncast.hopp.sovereigncast.ListenerApi
import space.sovereigncast.hopp.value.FakeRelay
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The optional Nostr identity, stage 1 (ADR 0021). */
class IdentityTest {
    private val now = 1_790_000_000L
    private val relay = FakeRelay()
    private val secrets = MemorySecretStore()
    private val identities = IdentityRepository(secrets, RelayPublisher(relay), { now })

    @Test
    fun aNewIdentityIsKeptAndItsProfilePublished() = runBlocking<Unit> {
        val published = mutableListOf<Event>()
        relay.listeners += { published += it }
        val took = identities.create("  Thorsten ")
        assertEquals((IdentityRepository.DEFAULT_RELAYS + IdentityRepository.INDEX_RELAYS).toSet(), took)
        val me = assertNotNull(identities.identity.value)
        assertEquals("Thorsten", me.name)
        assertEquals(me.pubkey, npubToHex(me.npub))
        val profile = published.first { it.kind == 0 }
        assertTrue(profile.isValid() && profile.pubkey == me.pubkey)
        assertEquals("Thorsten", Json.parseToJsonElement(profile.content).jsonObject["name"]?.jsonPrimitive?.content)
        val relays = published.first { it.kind == 10002 }
        assertEquals(IdentityRepository.DEFAULT_RELAYS, relays.tags.filter { it[0] == "r" }.map { it[1] })
        // Read back after a restart: the same key, the same identity.
        val again = IdentityRepository(secrets, RelayPublisher(relay), { now })
        assertEquals(me, again.identity.value)
        assertEquals(me.pubkey, again.signer()?.pubkey)
    }

    @Test
    fun aRelayThatRefusesIsNotCounted() = runBlocking<Unit> {
        relay.rejectEvents = true
        assertEquals(emptySet(), identities.create("X"))
        assertNotNull(identities.identity.value, "the identity exists without a relay")
    }

    @Test
    fun theBackupOpensWithItsPasswordAndIsNoted() = runBlocking<Unit> {
        identities.create("X")
        assertTrue(identities.identity.value?.exported == false)
        val backup = identities.export("correct horse")
        assertTrue(backup.startsWith("ncryptsec1"))
        val key = Nip49.decrypt(backup, "correct horse")
        assertEquals(identities.identity.value?.pubkey, LocalSigner(space.sovereigncast.hopp.nostr.KeyPair(key)).pubkey)
        assertTrue(identities.identity.value?.exported == true)
    }

    /** Security review 2026-10-20: the key store does not answer for the moment.
     *  Read as "no identity", Hopp offered to make one — and that replaced the
     *  key for good. Now the entry is unreadable, not absent: nothing is made or
     *  imported over it; it is back once the store answers; signing out is the way past it. */
    @Test
    fun anUnreadableKeyIsNeitherLostNorReplaced() = runBlocking<Unit> {
        identities.create("Thorsten")
        val me = assertNotNull(identities.identity.value)
        secrets.unreadable += "nostr_identity_key"
        val later = IdentityRepository(secrets, RelayPublisher(relay), { now })
        assertNull(later.identity.value)
        assertTrue(later.unreadable.value)
        assertFailsWith<space.sovereigncast.hopp.platform.SecretUnreadable> { later.create("Someone else") }
        val other = IdentityRepository(MemorySecretStore(), RelayPublisher(relay), { now })
        other.create("Other")
        assertFailsWith<space.sovereigncast.hopp.platform.SecretUnreadable> { later.importKey(other.nsec()!!) }
        assertNull(later.identity.value, "still nothing made in its place")
        secrets.unreadable -= "nostr_identity_key"
        assertEquals(me, IdentityRepository(secrets, RelayPublisher(relay), { now }).identity.value, "there again once the store answers")
        secrets.unreadable += "nostr_identity_key"
        val stuck = IdentityRepository(secrets, RelayPublisher(relay), { now })
        stuck.signOut()
        assertTrue(!stuck.unreadable.value)
        stuck.create("Anew")
        assertTrue(stuck.identity.value?.pubkey != me.pubkey)
    }

    @Test
    fun signingOutLeavesNoKeyBehind() = runBlocking<Unit> {
        identities.create("X")
        identities.signOut()
        assertNull(identities.identity.value)
        assertNull(identities.signer())
        assertNull(IdentityRepository(secrets, RelayPublisher(relay), { now }).identity.value)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun nip98NamesTheRequestItSigns() = runBlocking<Unit> {
        identities.create("X")
        val signer = identities.signer()!!
        val body = """{"voter_id":"v"}""".encodeToByteArray()
        val header = Nip98.authorization(signer, "https://pm.test/api/listener/v1/x", "post", body, now)
        assertTrue(header.startsWith("Nostr "))
        val event = Json.decodeFromString(Event.serializer(), Base64.decode(header.removePrefix("Nostr ")).decodeToString())
        assertTrue(event.isValid())
        assertEquals(27235, event.kind)
        assertEquals(now, event.created_at)
        assertEquals("https://pm.test/api/listener/v1/x", event.tag("u"))
        assertEquals("POST", event.tag("method"))
        assertEquals(Sha256.digest(body).toHex(), event.tag("payload"))
    }

    /** Votes go out signed; a signature the instance refuses costs no vote. */
    @Test
    fun aVoteIsSignedAndARefusedSignatureFallsBackToAnonymous() = runBlocking<Unit> {
        val auths = mutableListOf<String?>()
        var refuse = false
        val http = HttpClient(MockEngine { req ->
            auths += req.headers["Authorization"]
            assertEquals("""{"voter_id":"installation"}""", (req.body as TextContent).text)
            if (refuse && req.headers["Authorization"] != null) respondError(HttpStatusCode.Unauthorized, """{"error":{"code":"unauthorized","message":"clock"}}""")
            else respond("""{"already_voted":false}""")
        })
        identities.create("X")
        val api = ListenerApi(http) { url, method, body -> Nip98.authorization(identities.signer()!!, url, method, body, now) }
        api.vote("https://pm.test", "g", 32, "installation")
        assertTrue(auths.single()!!.startsWith("Nostr "))
        auths.clear(); refuse = true
        assertEquals(false, api.vote("https://pm.test", "g", 32, "installation").alreadyVoted)
        assertEquals(2, auths.size)
        assertNull(auths[1], "the retry still carried the refused signature")
        // Reading needs no signature.
        auths.clear()
        runCatching { api.suggestions("https://pm.test", "g") }
        assertNull(auths.single())
    }

    // ── stage 1b: a signer app (NIP-55) ─────────────────────────────────────

    /** Amber as Hopp sees it: a key elsewhere that signs on request. */
    private class FakeSignerApp(val keys: space.sovereigncast.hopp.nostr.KeyPair = space.sovereigncast.hopp.nostr.KeyPair.generate()) : space.sovereigncast.hopp.nostr.ExternalSigner {
        var reject = false
        var signWith: space.sovereigncast.hopp.nostr.KeyPair? = null
        val asked = mutableListOf<List<space.sovereigncast.hopp.nostr.SignerPermission>>()
        override fun available() = true
        override suspend fun login(permissions: List<space.sovereigncast.hopp.nostr.SignerPermission>): Pair<String, String> {
            asked += permissions
            if (reject) throw space.sovereigncast.hopp.nostr.SignerRejected()
            return keys.publicKeyHex to "com.greenart7c3.nostrsigner"
        }
        override suspend fun signEvent(signerPackage: String, eventJson: String, eventId: String, currentUser: String): space.sovereigncast.hopp.nostr.SignerResult {
            assertEquals("com.greenart7c3.nostrsigner", signerPackage)
            assertEquals(keys.publicKeyHex, currentUser)
            if (reject) throw space.sovereigncast.hopp.nostr.SignerRejected()
            val o = Json.parseToJsonElement(eventJson).jsonObject
            val tags = o["tags"]!!.jsonArray.map { t -> t.jsonArray.map { it.jsonPrimitive.content } }
            val e = Event.sign(signWith ?: keys, o["kind"]!!.jsonPrimitive.content.toInt(), o["content"]!!.jsonPrimitive.content, tags, o["created_at"]!!.jsonPrimitive.content.toLong())
            return space.sovereigncast.hopp.nostr.SignerResult(Json.encodeToString(Event.serializer(), e), null)
        }
    }

    @Test
    fun aSignerAppSignsInReadsTheNameAndSigns() = runBlocking<Unit> {
        val app = FakeSignerApp()
        // The listener's existing profile, on a relay.
        relay.publish(Event.sign(app.keys, 0, """{"name":"tg","display_name":"Thorsten"}""", emptyList(), now - 100))
        val repo = IdentityRepository(secrets, RelayPublisher(relay), { now }, app)
        val published = mutableListOf<Event>()
        relay.listeners += { published += it }
        val me = repo.signInWithSigner()
        assertEquals(NostrIdentity.Source.Amber, me.source)
        assertEquals("Thorsten", me.name)
        assertEquals(false, me.holdsKey)
        assertEquals(space.sovereigncast.hopp.nostr.ExternalSigner.PERMISSIONS, app.asked.single())
        assertTrue(space.sovereigncast.hopp.nostr.SignerPermission("sign_event", Nip98.KIND) in app.asked.single(), "NIP-98 for Sovereigncast; comments since stage 3")
        assertTrue(published.isEmpty(), "Hopp wrote to an identity it did not make")
        assertEquals(emptySet(), repo.publishProfile())
        val header = Nip98.authorization(repo.signer()!!, "https://pm.test/x", "POST", null, now)
        @OptIn(ExperimentalEncodingApi::class)
        val event = Json.decodeFromString(Event.serializer(), Base64.decode(header.removePrefix("Nostr ")).decodeToString())
        assertTrue(event.isValid() && event.pubkey == app.keys.publicKeyHex)
        // After a restart: the same identity, still signing through the app, no key here.
        val again = IdentityRepository(secrets, RelayPublisher(relay), { now }, app)
        assertEquals(me, again.identity.value)
        assertTrue(again.signer() is space.sovereigncast.hopp.nostr.ExternalNostrSigner)
    }

    @Test
    fun saidNoInTheSignerAppLeavesNoIdentity() = runBlocking<Unit> {
        val app = FakeSignerApp().apply { reject = true }
        val repo = IdentityRepository(secrets, RelayPublisher(relay), { now }, app)
        kotlin.test.assertFailsWith<space.sovereigncast.hopp.nostr.SignerRejected> { repo.signInWithSigner() }
        assertNull(repo.identity.value)
    }

    /** A signer app is someone else's code: an event signed by another key is refused. */
    @Test
    fun aSignatureByAnotherKeyIsRefused() = runBlocking<Unit> {
        val app = FakeSignerApp()
        val repo = IdentityRepository(secrets, RelayPublisher(relay), { now }, app)
        repo.signInWithSigner()
        app.signWith = space.sovereigncast.hopp.nostr.KeyPair.generate()
        kotlin.test.assertFailsWith<space.sovereigncast.hopp.nostr.SignerUnavailable> {
            repo.signer()!!.sign(space.sovereigncast.hopp.nostr.UnsignedEvent(Nip98.KIND, "", emptyList(), now))
        }
    }

    /**
     * Device feedback 2026-10-16: the listener's profile was on none of Hopp's
     * relays — only an index had it, and their relay list. Found there, and on
     * the relays that list names.
     */
    @Test
    fun aProfileIsFoundThroughTheRelayListOnAnIndex() = runBlocking<Unit> {
        val app = FakeSignerApp()
        val index = FakeRelay()
        val theirRelay = FakeRelay()
        val connector = space.sovereigncast.hopp.nostr.RelayConnector { url ->
            when (url) {
                "wss://purplepag.es" -> index.connect(url)
                "wss://relay.their.example" -> theirRelay.connect(url)
                else -> FakeRelay().connect(url)
            }
        }
        index.publish(Event.sign(app.keys, 10002, "", listOf(listOf("r", "wss://relay.their.example/"), listOf("r", "wss://read.only.example", "read")), now - 50))
        theirRelay.publish(Event.sign(app.keys, 0, """{"name":"Altbierjupp","picture":"https://img.example/a.png"}""", emptyList(), now - 100))
        val repo = IdentityRepository(secrets, RelayPublisher(connector), { now }, app)
        val me = repo.signInWithSigner()
        assertEquals("Altbierjupp", me.name)
        assertEquals("https://img.example/a.png", me.picture)
    }

    /** Device feedback 2026-10-19: one's own relay on the list — written as every client reads it. */
    @Test
    fun aRelayAddressIsKeptOneWay() {
        assertEquals("wss://relay.example.com", IdentityRepository.relayAddress(" relay.Example.com/ "))
        assertEquals("wss://relay.example.com", IdentityRepository.relayAddress("wss://relay.example.com:443"))
        assertEquals("wss://relay.example.com:7777/nostr", IdentityRepository.relayAddress("wss://relay.example.com:7777/nostr/"))
        assertNull(IdentityRepository.relayAddress("ws://relay.example.com"), "no TLS: not for the list")
        assertNull(IdentityRepository.relayAddress("https://relay.example.com"))
        assertNull(IdentityRepository.relayAddress("umbrel"))
    }

    /** An imported key brought its relay list with it — Hopp writes there, not to its own three. */
    @Test
    fun anImportedKeyWritesToItsOwnRelays() = runBlocking<Unit> {
        val keys = space.sovereigncast.hopp.nostr.KeyPair.generate()
        relay.publish(Event.sign(keys, 10002, "", listOf(listOf("r", "wss://mine.example/"), listOf("r", "wss://read.example", "read")), now - 50))
        identities.importKey(space.sovereigncast.hopp.nostr.Bech32.encode("nsec", keys.secretKey))
        assertEquals(listOf("wss://mine.example"), identities.writeRelays())
        assertEquals(listOf("wss://mine.example", "wss://read.example"), identities.readRelays())
    }

    /**
     * A relay added in Hopp joins the list as it stands on the relays — one
     * another app changed meanwhile keeps what it added — and the last relay
     * written to cannot be removed.
     */
    @Test
    fun aRelayJoinsTheListAsItStands() = runBlocking<Unit> {
        identities.create("X")
        val keys = space.sovereigncast.hopp.nostr.KeyPair.fromHex(secrets.get("nostr_identity_key")!!)
        // The other app's list is newer than Hopp's.
        relay.publish(Event.sign(keys, 10002, "", listOf(listOf("r", "wss://other.example"), listOf("r", "wss://nos.lol", "write")), now + 10))
        val published = mutableListOf<Event>()
        relay.listeners += { published += it }
        val list = identities.addRelay("my.relay.example")
        assertEquals(listOf("wss://other.example", "wss://nos.lol", "wss://my.relay.example"), list.map { it.url })
        assertTrue(list[1].write && !list[1].read, "a marker stays")
        assertEquals(list.map { it.url }, published.filter { it.kind == 10002 }.distinctBy { it.id }.single().tags.map { it[1] })
        assertEquals(list, identities.identity.value!!.relays)
        assertTrue("wss://my.relay.example" in identities.writeRelays())
        removingAllLeavesOneToWriteTo(identities)
    }

    /** Security audit 2026-10-20, M-08: the relays cannot be read, publishing
     *  works. The other app's newer list may be there: Hopp's local copy is
     *  not written over it; once the relays answer, the change joins it. */
    @Test
    fun aRelayListThatCannotBeReadIsNotReplaced() = runBlocking<Unit> {
        identities.create("X")
        val keys = space.sovereigncast.hopp.nostr.KeyPair.fromHex(secrets.get("nostr_identity_key")!!)
        relay.publish(Event.sign(keys, 10002, "", listOf(listOf("r", "wss://other.example")), now + 10))
        val published = mutableListOf<Event>()
        relay.listeners += { published += it }
        relay.readsFail = true
        assertFailsWith<space.sovereigncast.hopp.comments.ListNotRead> { identities.addRelay("my.relay.example") }
        assertTrue(published.none { it.kind == 10002 }, "nothing published")
        assertEquals(IdentityRepository.DEFAULT_RELAYS, identities.identity.value!!.relays.map { it.url }, "the local copy as it was")
        relay.readsFail = false
        assertEquals(listOf("wss://other.example", "wss://my.relay.example"), identities.addRelay("my.relay.example").map { it.url })
    }

    private suspend fun removingAllLeavesOneToWriteTo(repo: IdentityRepository) {
        var list = repo.relays()
        for (r in list.toList()) list = repo.removeRelay(r.url)
        assertEquals(1, list.count { it.write }, "one relay to write to stays: $list")
    }

    @Test
    fun aPictureThatIsNotHttpsIsLeftOut() = runBlocking<Unit> {
        val app = FakeSignerApp()
        relay.publish(Event.sign(app.keys, 0, """{"name":"X","picture":"http://img.example/a.png"}""", emptyList(), now - 100))
        val me = IdentityRepository(secrets, RelayPublisher(relay), { now }, app).signInWithSigner()
        assertEquals("X", me.name)
        assertNull(me.picture)
    }

    /** Device feedback 2026-10-16: the key out as nsec (every app reads it) and back in, as nsec or ncryptsec. */
    @Test
    fun aKeyGoesOutAsNsecAndComesBack() = runBlocking<Unit> {
        identities.create("X")
        val me = identities.identity.value!!
        val nsec = identities.nsec()!!
        assertTrue(nsec.startsWith("nsec1"))
        assertTrue(!me.exported)
        identities.markBackedUp()
        assertTrue(identities.identity.value!!.exported)
        val locked = identities.export("correct horse")
        identities.signOut()

        // Another device: the nsec as it came from the password manager.
        val other = IdentityRepository(MemorySecretStore(), RelayPublisher(relay), { now })
        assertEquals(me.pubkey, other.importKey("  $nsec ").pubkey)
        assertEquals(me.pubkey, other.signer()?.pubkey, "it signs")
        assertTrue(other.identity.value!!.exported && other.identity.value!!.holdsKey)

        // The ncryptsec: its password first, a wrong one refused.
        val third = IdentityRepository(MemorySecretStore(), RelayPublisher(relay), { now })
        assertEquals(KeyImportException.Reason.NeedsPassword, kotlin.test.assertFailsWith<KeyImportException> { third.importKey(locked) }.reason)
        assertEquals(KeyImportException.Reason.WrongPassword, kotlin.test.assertFailsWith<KeyImportException> { third.importKey(locked, "wrong") }.reason)
        assertNull(third.identity.value)
        assertEquals(me.pubkey, third.importKey(locked, "correct horse").pubkey)
        // And what is not a key at all.
        assertEquals(KeyImportException.Reason.Unreadable, kotlin.test.assertFailsWith<KeyImportException> { third.importKey("npub1" + "q".repeat(58)) }.reason)
        assertEquals(KeyImportException.Reason.Unreadable, kotlin.test.assertFailsWith<KeyImportException> { third.importKey("nsec1garbage") }.reason)
    }

    @Test
    fun aKeyHeldElsewhereHasNoNsec() = runBlocking<Unit> {
        assertNull(identities.nsec())
    }
}
