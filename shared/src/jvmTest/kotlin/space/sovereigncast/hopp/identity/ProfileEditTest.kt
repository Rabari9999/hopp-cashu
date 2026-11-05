package space.sovereigncast.hopp.identity

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.sovereigncast.hopp.crypto.Sha256
import space.sovereigncast.hopp.crypto.toHex
import space.sovereigncast.hopp.nostr.Blossom
import space.sovereigncast.hopp.nostr.BlossomFailed
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.nostr.LocalSigner
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.platform.MemorySecretStore
import space.sovereigncast.hopp.value.FakeRelay
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Name and picture set from Hopp (maintainer 2026-11-03): the rest of the profile kept, nothing written blind. */
class ProfileEditTest {
    private val now = 1_790_000_000L
    private val relay = FakeRelay()
    private val secrets = MemorySecretStore()
    private val identities = IdentityRepository(secrets, RelayPublisher(relay), { now })

    private fun keys() = KeyPair.fromHex(secrets.get("nostr_identity_key")!!)

    @Test
    fun aChangeKeepsEverythingButNameAndPicture() = runBlocking<Unit> {
        identities.create("Old")
        relay.publish(Event.sign(keys(), 0, """{"name":"Old","about":"Listens a lot","lud16":"me@wallet.example","nip05":"me@example.com","picture":"https://img.example/old.png"}""", emptyList(), now + 10))
        val read = assertIs<ProfileRead.Found>(identities.readProfile())
        val published = mutableListOf<Event>()
        relay.listeners += { published += it }
        val took = identities.saveProfile(" New ", "https://img.example/new.jpg", read.content)
        assertTrue(took.isNotEmpty())
        val profile = Json.parseToJsonElement(published.filter { it.kind == 0 }.distinctBy { it.id }.single().content).jsonObject
        assertEquals("New", profile["name"]?.jsonPrimitive?.content)
        assertEquals("New", profile["display_name"]?.jsonPrimitive?.content)
        assertEquals("https://img.example/new.jpg", profile["picture"]?.jsonPrimitive?.content)
        assertEquals("Listens a lot", profile["about"]?.jsonPrimitive?.content)
        assertEquals("me@wallet.example", profile["lud16"]?.jsonPrimitive?.content)
        assertEquals("me@example.com", profile["nip05"]?.jsonPrimitive?.content)
        assertEquals("New", identities.identity.value?.name)
        assertEquals("https://img.example/new.jpg", identities.identity.value?.picture)
    }

    @Test
    fun aPictureTakenOutLeavesTheProfile() = runBlocking<Unit> {
        identities.create("X")
        val published = mutableListOf<Event>()
        relay.listeners += { published += it }
        identities.saveProfile("X", null, Json.parseToJsonElement("""{"name":"X","picture":"https://img.example/a.png","about":"a"}""").jsonObject)
        val profile = Json.parseToJsonElement(published.filter { it.kind == 0 }.distinctBy { it.id }.single().content).jsonObject
        assertTrue("picture" !in profile)
        assertEquals("a", profile["about"]?.jsonPrimitive?.content)
    }

    @Test
    fun noProfileOnRelaysThatAnsweredIsANewAccount() = runBlocking<Unit> {
        identities.create("X")
        // The profile create() published taken away: as a new account from a signer app.
        relay.hide = { it.kind == 0 }
        assertEquals(ProfileRead.Empty, identities.readProfile())
    }

    @Test
    fun noAnswerIsNotNone() = runBlocking<Unit> {
        identities.create("X")
        relay.readsFail = true
        assertEquals(ProfileRead.Unknown, identities.readProfile())
    }

    @Test
    fun theServersAreTheListenersOwnElseHopps() = runBlocking<Unit> {
        identities.create("X")
        assertEquals(listOf(Blossom.DEFAULT_SERVER), identities.blossomServers())
        relay.publish(Event.sign(keys(), Blossom.SERVER_LIST_KIND, "", listOf(listOf("server", "https://blossom.mine.example/"), listOf("server", "http://plain.example"), listOf("server", "https://second.example")), now + 5))
        assertEquals(listOf("https://blossom.mine.example", "https://second.example"), identities.blossomServers())
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun anUploadIsAllowedForThisFileOnlyAndItsAnswerChecked() = runBlocking<Unit> {
        val signer = LocalSigner(KeyPair.generate())
        val bytes = ByteArray(2048) { (it % 251).toByte() }
        val hash = Sha256.digest(bytes).toHex()
        var auth: Event? = null
        var answer = """{"url":"https://blossom.example/$hash.jpg","sha256":"$hash","size":2048,"type":"image/jpeg"}"""
        val http = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals("https://blossom.example/upload", request.url.toString())
            val header = request.headers["Authorization"]!!
            assertTrue(header.startsWith("Nostr "))
            auth = Json.decodeFromString(Event.serializer(), Base64.decode(header.removePrefix("Nostr ")).decodeToString())
            assertEquals(bytes.toList(), (request.body as OutgoingContent.ByteArrayContent).bytes().toList())
            respond(answer, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        val blossom = Blossom(http)
        assertEquals("https://blossom.example/$hash.jpg", blossom.upload("https://blossom.example/", bytes, "image/jpeg", signer, now))
        val event = auth!!
        assertTrue(event.isValid())
        assertEquals(Blossom.KIND, event.kind)
        assertTrue(listOf("x", hash) in event.tags)
        assertTrue(listOf("t", "upload") in event.tags)
        assertTrue(listOf("expiration", (now + 300).toString()) in event.tags)
        // Another file than this one, no word on which file, or an address that is no https: refused.
        answer = """{"url":"https://blossom.example/x.jpg","sha256":"${"0".repeat(64)}"}"""
        assertFailsWith<BlossomFailed> { blossom.upload("https://blossom.example", bytes, "image/jpeg", signer, now) }
        answer = """{"url":"https://blossom.example/x.jpg"}"""
        assertFailsWith<BlossomFailed> { blossom.upload("https://blossom.example", bytes, "image/jpeg", signer, now) }
        answer = """{"url":"http://blossom.example/$hash.jpg","sha256":"$hash"}"""
        assertFailsWith<BlossomFailed> { blossom.upload("https://blossom.example", bytes, "image/jpeg", signer, now) }
    }

    @Test
    fun aServerThatSaysNoIsAFailureWithItsReason() = runBlocking<Unit> {
        val http = HttpClient(MockEngine { respond("", HttpStatusCode.PaymentRequired, headersOf("X-Reason", "Payment required")) })
        val e = assertFailsWith<BlossomFailed> { Blossom(http).upload("https://b.example", byteArrayOf(1), "image/jpeg", LocalSigner(KeyPair.generate()), now) }
        assertEquals("Payment required", e.reason)
    }

    @Test
    fun anEmptyBaseMakesAProfileOfNameAndPicture() = runBlocking<Unit> {
        identities.create("X")
        val published = mutableListOf<Event>()
        relay.listeners += { published += it }
        identities.saveProfile("Y", "https://img.example/y.jpg", JsonObject(emptyMap()))
        val profile = Json.parseToJsonElement(published.filter { it.kind == 0 }.distinctBy { it.id }.single().content).jsonObject
        assertEquals(setOf("name", "display_name", "picture"), profile.keys)
    }
}
