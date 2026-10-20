package space.sovereigncast.hopp.identity

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.nostr.BunkerSigner
import space.sovereigncast.hopp.nostr.BunkerUri
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.nostr.Nip44
import space.sovereigncast.hopp.nostr.Nip46Exception
import space.sovereigncast.hopp.nostr.Nip98
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.nostr.UnsignedEvent
import space.sovereigncast.hopp.platform.MemorySecretStore
import space.sovereigncast.hopp.value.FakeRelay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A remote signer as nsec.app or Amber's bunker mode answers NIP-46, on the
 * test relay: its own key for the conversation, the listener's key to sign.
 */
private class FakeBunker(val relay: FakeRelay, val now: () -> Long) {
    val bunkerKeys = KeyPair.generate()
    val userKeys = KeyPair.generate()
    var secret: String? = "s3cret"
    var authUrlFirst: String? = null
    var signWith: KeyPair? = null
    val methods = mutableListOf<String>()
    val params = mutableListOf<List<String>>()

    val address get() = "bunker://${bunkerKeys.publicKeyHex}?relay=wss%3A%2F%2Frelay.bunker.test&secret=${secret.orEmpty()}"

    init {
        relay.listeners += { e -> if (e.kind == 24133 && e.tag("p") == bunkerKeys.publicKeyHex) answer(e) }
    }

    private fun answer(e: Event) {
        val key = Nip44.conversationKey(bunkerKeys.secretKey, e.pubkey.hexToBytes())
        val o = Json.parseToJsonElement(Nip44.decrypt(e.content, key)).jsonObject
        val id = o["id"]!!.jsonPrimitive.content
        val method = o["method"]!!.jsonPrimitive.content
        val p = o["params"]!!.jsonArray.map { it.jsonPrimitive.content }
        methods += method; params += p
        fun reply(result: String?, error: String? = null) {
            val content = buildJsonObject { put("id", id); result?.let { put("result", it) }; error?.let { put("error", it) } }.toString()
            relay.publish(Event.sign(bunkerKeys, 24133, Nip44.encrypt(content, key), listOf(listOf("p", e.pubkey)), now()))
        }
        authUrlFirst?.let { url -> authUrlFirst = null; reply("auth_url", url) }
        when (method) {
            "connect" -> if (secret != null && p.getOrNull(1) != secret) reply(null, "invalid secret") else reply("ack")
            "get_public_key" -> reply(userKeys.publicKeyHex)
            "switch_relays" -> reply("null")
            "sign_event" -> {
                val ev = Json.parseToJsonElement(p[0]).jsonObject
                val tags = ev["tags"]!!.jsonArray.map { t -> t.jsonArray.map { it.jsonPrimitive.content } }
                val signed = Event.sign(signWith ?: userKeys, ev["kind"]!!.jsonPrimitive.content.toInt(), ev["content"]!!.jsonPrimitive.content, tags, ev["created_at"]!!.jsonPrimitive.content.toLong())
                reply(Json.encodeToString(Event.serializer(), signed))
            }
            "nip44_encrypt", "nip44_decrypt" -> {
                val k = Nip44.conversationKey(userKeys.secretKey, p[0].hexToBytes())
                reply(if (method == "nip44_encrypt") Nip44.encrypt(p[1], k) else Nip44.decrypt(p[1], k))
            }
            "logout" -> reply("ack")
            else -> reply(null, "unsupported")
        }
    }

    /** Takes up a nostrconnect:// code as a signer app does after scanning it. */
    fun takeUp(uri: String, withSecret: String? = null) {
        val client = uri.removePrefix("nostrconnect://").substringBefore('?')
        val secretParam = Regex("secret=([^&]+)").find(uri)!!.groupValues[1]
        val key = Nip44.conversationKey(bunkerKeys.secretKey, client.hexToBytes())
        val content = buildJsonObject { put("id", "x"); put("result", withSecret ?: secretParam) }.toString()
        relay.publish(Event.sign(bunkerKeys, 24133, Nip44.encrypt(content, key), listOf(listOf("p", client)), now()))
    }
}

/** NIP-46, stage 1c of ADR 0021: a remote signer ("bunker"). */
class BunkerTest {
    private val now = 1_790_000_000L
    private val relay = FakeRelay()
    private val secrets = MemorySecretStore()
    private val bunker = FakeBunker(relay) { now }
    private val identities = IdentityRepository(secrets, RelayPublisher(relay), { now })

    @Test
    fun aBunkerAddressIsRead() {
        val b = BunkerUri.parse("bunker://${"ab".repeat(32)}?relay=wss%3A%2F%2Fr1.test&relay=wss://r2.test/&secret=xyz")
        assertEquals("ab".repeat(32), b.remoteSignerPubkey)
        assertEquals(listOf("wss://r1.test", "wss://r2.test"), b.relays)
        assertEquals("xyz", b.secret)
        assertFailsWith<Nip46Exception> { BunkerUri.parse("bunker://nokey?relay=wss://r.test") }
        assertFailsWith<Nip46Exception> { BunkerUri.parse("bunker://${"ab".repeat(32)}") }
        assertFailsWith<Nip46Exception> { BunkerUri.parse("nostrconnect://${"ab".repeat(32)}?relay=wss://r.test") }
    }

    @Test
    fun aBunkerSignsInAndSignsNip98() = runBlocking<Unit> {
        relay.publish(Event.sign(bunker.userKeys, 0, """{"name":"Bunkered"}""", emptyList(), now - 100))
        val me = identities.signInWithBunker(bunker.address)
        assertEquals(NostrIdentity.Source.Bunker, me.source)
        assertEquals(bunker.userKeys.publicKeyHex, me.pubkey)
        assertEquals("Bunkered", me.name)
        assertEquals(false, me.holdsKey)
        // connect names the signer, the secret, and asks for NIP-98 for good.
        val connect = bunker.params[bunker.methods.indexOf("connect")]
        assertEquals(listOf(bunker.bunkerKeys.publicKeyHex, "s3cret", IdentityRepository.BUNKER_PERMS), connect.take(3))
        assertEquals("Hopp", Json.parseToJsonElement(connect[3]).jsonObject["name"]?.jsonPrimitive?.contentOrNull)
        val signer = identities.signer()
        assertTrue(signer is BunkerSigner)
        val event = signer.sign(UnsignedEvent(Nip98.KIND, "", listOf(listOf("u", "https://pm.test/x")), now))
        assertTrue(event.isValid() && event.pubkey == bunker.userKeys.publicKeyHex)
        // After a restart: the same identity, signing through the bunker.
        val again = IdentityRepository(secrets, RelayPublisher(relay), { now })
        assertEquals(me, again.identity.value)
        assertTrue(again.signer() is BunkerSigner)
    }

    @Test
    fun aBunkerEncryptsToTheListenerAndAKeyHereOpensIt() = runBlocking<Unit> {
        identities.signInWithBunker(bunker.address)
        assertTrue("nip44_encrypt" in IdentityRepository.BUNKER_PERMS && "nip44_decrypt" in IdentityRepository.BUNKER_PERMS)
        val signer = identities.signer()!!
        val sealed = signer.encryptToSelf("""{"v":1}""")
        assertEquals(listOf(bunker.userKeys.publicKeyHex, """{"v":1}"""), bunker.params[bunker.methods.lastIndexOf("nip44_encrypt")])
        // The same key on another device, held there: it opens what the bunker sealed, and back.
        val local = space.sovereigncast.hopp.nostr.LocalSigner(bunker.userKeys)
        assertEquals("""{"v":1}""", local.decryptFromSelf(sealed))
        assertEquals("back", signer.decryptFromSelf(local.encryptToSelf("back")))
    }

    @Test
    fun aWrongSecretIsRefused() = runBlocking<Unit> {
        val address = bunker.address.replace("s3cret", "wrong")
        assertFailsWith<Nip46Exception> { identities.signInWithBunker(address) }
        assertNull(identities.identity.value)
    }

    /** The signer wants a confirmation on its page first; the answer follows on the same id. */
    @Test
    fun anAuthUrlIsShownAndTheAnswerAwaited() = runBlocking<Unit> {
        bunker.authUrlFirst = "https://signer.test/confirm/1"
        val shown = mutableListOf<String>()
        identities.signInWithBunker(bunker.address) { shown += it }
        assertEquals(listOf("https://signer.test/confirm/1"), shown)
        assertEquals(bunker.userKeys.publicKeyHex, identities.identity.value?.pubkey)
    }

    @Test
    fun anEventSignedByAnotherKeyIsRefused() = runBlocking<Unit> {
        identities.signInWithBunker(bunker.address)
        bunker.signWith = KeyPair.generate()
        assertFailsWith<Nip46Exception> { identities.signer()!!.sign(UnsignedEvent(Nip98.KIND, "", emptyList(), now)) }
    }

    /** The other way round: Hopp shows a code, the signer takes it up. */
    @Test
    fun aNostrConnectCodeIsTakenUp() = runBlocking<Unit> {
        val offer = identities.nostrConnectOffer()
        assertTrue(offer.uri.startsWith("nostrconnect://${offer.clientKeys.publicKeyHex}?relay=wss%3A%2F%2Frelay.nsec.app"))
        assertTrue("perms=sign_event%3A27235" in offer.uri && "name=Hopp" in offer.uri)
        val waiting = async { identities.signInWithNostrConnect(offer) }
        delay(100)
        bunker.takeUp(offer.uri)
        assertEquals(bunker.userKeys.publicKeyHex, waiting.await().pubkey)
    }

    /** A connect answer without the code's secret is someone posing as the signer. */
    @Test
    fun aNostrConnectAnswerWithoutTheSecretIsIgnored() = runBlocking<Unit> {
        val offer = identities.nostrConnectOffer()
        val waiting = async {
            runCatching { kotlinx.coroutines.withTimeout(1_000) { identities.signInWithNostrConnect(offer) } }
        }
        delay(100)
        bunker.takeUp(offer.uri, withSecret = "guessed")
        assertTrue(waiting.await().isFailure)
        assertNull(identities.identity.value)
    }

    @Test
    fun signingOutTellsTheSignerAndForgetsHoppsKey() = runBlocking<Unit> {
        identities.signInWithBunker(bunker.address)
        identities.endRemoteSession()
        identities.signOut()
        assertEquals("logout", bunker.methods.last())
        assertNull(identities.signer())
        assertNull(IdentityRepository(secrets, RelayPublisher(relay), { now }).identity.value)
    }
}
