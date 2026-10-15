package space.sovereigncast.hopp.identity

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
        assertEquals(IdentityRepository.DEFAULT_RELAYS.toSet(), took)
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
}
