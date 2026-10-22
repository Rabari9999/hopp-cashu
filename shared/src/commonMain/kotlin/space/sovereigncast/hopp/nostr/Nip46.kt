package space.sovereigncast.hopp.nostr

import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.crypto.secureRandomBytes
import space.sovereigncast.hopp.crypto.toHex
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The remote signer answered with an error, or something that is not an answer. */
class Nip46Exception(message: String) : Exception(message)

private fun isHexKey(s: String) = s.length == 64 && s.all { it in "0123456789abcdef" }

/** `bunker://<remote-signer-pubkey>?relay=…&secret=…` — what a remote signer hands out (NIP-46). */
data class BunkerUri(val remoteSignerPubkey: String, val relays: List<String>, val secret: String?) {
    companion object {
        fun parse(text: String): BunkerUri {
            val trimmed = text.trim()
            if (!trimmed.startsWith("bunker://")) throw Nip46Exception("not a bunker:// address")
            val url = runCatching { Url(trimmed) }.getOrElse { throw Nip46Exception("unreadable bunker:// address") }
            val pubkey = url.host.lowercase().takeIf(::isHexKey) ?: throw Nip46Exception("no signer key in the address")
            val relays = url.parameters.getAll("relay").orEmpty().map { it.trim().trimEnd('/') }
                .filter { it.startsWith("wss://") || it.startsWith("ws://") }.distinct()
            if (relays.isEmpty()) throw Nip46Exception("no relay in the address")
            return BunkerUri(pubkey, relays, url.parameters["secret"]?.takeIf { it.isNotBlank() })
        }
    }
}

/**
 * `nostrconnect://<client-pubkey>?relay=…&secret=…` — what Hopp shows a signer
 * so that it connects to Hopp (the other direction of NIP-46).
 */
data class NostrConnectOffer(val clientKeys: KeyPair, val relays: List<String>, val secret: String, val perms: String, val name: String) {
    val uri: String
        get() = buildString {
            append("nostrconnect://").append(clientKeys.publicKeyHex).append('?')
            append(relays.joinToString("&") { "relay=" + encode(it) })
            append("&secret=").append(secret)
            append("&perms=").append(encode(perms))
            append("&name=").append(encode(name))
        }

    companion object {
        fun create(relays: List<String>, perms: String, name: String = "Hopp") =
            NostrConnectOffer(KeyPair.generate(), relays, secureRandomBytes(8).toHex(), perms, name)

        private fun encode(s: String) = s.encodeURLParameter()
    }
}

/**
 * The client side of NIP-46: requests to a remote signer ("bunker") as
 * NIP-44-encrypted kind-24133 events over its relays. One request, one
 * session, as Hopp's NWC client does. A signer may answer `auth_url` first —
 * the listener opens it ([onAuthUrl]) and the real answer follows on the same
 * id, so the wait is long.
 */
class Nip46Client(
    private val clientKeys: KeyPair,
    val remoteSignerPubkey: String,
    relays: List<String>,
    private val connector: RelayConnector,
    private val now: () -> Long,
    private val timeout: Duration = 2.minutes,
    private val onAuthUrl: (String) -> Unit = {},
) {
    var relays: List<String> = relays
        private set
    private val json = Json { ignoreUnknownKeys = true }
    private val conversationKey by lazy { Nip44.conversationKey(clientKeys.secretKey, remoteSignerPubkey.hexToBytes()) }

    suspend fun connect(secret: String?, perms: String, name: String = "Hopp"): String =
        request("connect", listOf(remoteSignerPubkey, secret.orEmpty(), perms, buildJsonObject { put("name", name) }.toString()))

    suspend fun getPublicKey(): String =
        request("get_public_key", emptyList()).trim().lowercase().takeIf(::isHexKey) ?: throw Nip46Exception("not a public key")

    /**
     * [getPublicKey] right after `connect`: a signer still saving the new connection
     * (Amber writes it after a short pause) answers "no permission" for a moment —
     * asked again then, a few times.
     */
    suspend fun getPublicKeyPatiently(tries: Int = 4, pause: Duration = 1.5.seconds): String {
        repeat(tries - 1) {
            try { return getPublicKey() } catch (e: Nip46Exception) {
                if (e.message?.contains("permission", ignoreCase = true) != true) throw e
            }
            kotlinx.coroutines.delay(pause)
        }
        return getPublicKey()
    }

    /** The signer's own relays, if it wants others (NIP-46 "switching relays"). */
    suspend fun switchRelays() {
        val answer = runCatching { request("switch_relays", emptyList(), 20.seconds) }.getOrNull() ?: return
        val list = runCatching { json.parseToJsonElement(answer).jsonArray.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } }.getOrNull()
            ?.filter { it.startsWith("wss://") || it.startsWith("ws://") }.orEmpty()
        if (list.isNotEmpty()) relays = list
    }

    /** `sign_event`: the event the signer signed, as JSON. */
    suspend fun signEvent(event: UnsignedEvent): String = request("sign_event", listOf(buildJsonObject {
        put("kind", event.kind); put("content", event.content); put("created_at", event.createdAt)
        put("tags", JsonArray(event.tags.map { tag -> JsonArray(tag.map(::JsonPrimitive)) }))
    }.toString()))

    /** `nip44_encrypt` / `nip44_decrypt` with the listener's key for [peer]. */
    suspend fun nip44(encrypt: Boolean, peer: String, text: String): String =
        request(if (encrypt) "nip44_encrypt" else "nip44_decrypt", listOf(peer, text))

    /** A courtesy: the signer may forget this session (NIP-46 "ending a session"). */
    suspend fun logout() { runCatching { request("logout", emptyList(), 10.seconds) } }

    suspend fun request(method: String, params: List<String>, wait: Duration = timeout): String {
        val id = secureRandomBytes(8).toHex()
        val content = buildJsonObject { put("id", id); put("method", method); putJsonArray("params") { params.forEach { add(JsonPrimitive(it)) } } }.toString()
        val event = Event.sign(clientKeys, KIND, Nip44.encrypt(content, conversationKey), listOf(listOf("p", remoteSignerPubkey)), now())
        return withSession { session ->
            coroutineScope {
                val answer = async(start = CoroutineStart.UNDISPATCHED) {
                    session.incoming.mapNotNull { text -> response(text, id) }.first { it.result != "auth_url" || it.error == null || run { onAuthUrl(it.error); false } }
                }
                session.send(buildJsonArray {
                    add(JsonPrimitive("REQ")); add(JsonPrimitive(SUB))
                    add(buildJsonObject {
                        putJsonArray("kinds") { add(JsonPrimitive(KIND)) }
                        putJsonArray("#p") { add(JsonPrimitive(clientKeys.publicKeyHex)) }
                        putJsonArray("authors") { add(JsonPrimitive(remoteSignerPubkey)) }
                        put("since", now() - 10)
                    })
                }.toString())
                session.send(buildJsonArray { add(JsonPrimitive("EVENT")); add(json.encodeToJsonElement(Event.serializer(), event)) }.toString())
                val r = withTimeout(wait) { answer.await() }
                r.error?.let { throw Nip46Exception(it) }
                r.result ?: throw Nip46Exception("an empty answer")
            }
        }
    }

    private data class Response(val id: String, val result: String?, val error: String?)

    /** A decrypted answer to [id] from the signer, or null for anything else on the relay. */
    private fun response(text: String, id: String): Response? = runCatching {
        val arr = json.parseToJsonElement(text).jsonArray
        if (arr[0].jsonPrimitive.content != "EVENT") return null
        val e = json.decodeFromJsonElement(Event.serializer(), arr[2])
        if (e.kind != KIND || e.pubkey != remoteSignerPubkey || !e.isValid()) return null
        val o = json.parseToJsonElement(Nip44.decrypt(e.content, conversationKey)).jsonObject
        val r = Response(o["id"]!!.jsonPrimitive.content, (o["result"] as? JsonPrimitive)?.contentOrNull, (o["error"] as? JsonPrimitive)?.contentOrNull)
        r.takeIf { it.id == id }
    }.getOrNull()

    private suspend fun <T> withSession(block: suspend (RelaySession) -> T): T {
        var last: Exception? = null
        for (url in relays) {
            val session = try { connector.connect(url) } catch (e: Exception) { last = e; continue }
            try { return block(session) } finally { runCatching { session.close() } }
        }
        throw Nip46Exception("no relay of the signer reachable" + (last?.message?.let { ": $it" } ?: ""))
    }

    companion object {
        const val KIND = 24133
        private const val SUB = "hopp-nip46"

        /**
         * Waits for a signer to take up [offer] (the `nostrconnect://` direction):
         * its `connect` answer names it, and must carry the offer's secret — else
         * anyone could pose as the listener's signer (NIP-46). Returns the client
         * for further requests.
         */
        suspend fun awaitConnect(offer: NostrConnectOffer, connector: RelayConnector, now: () -> Long, wait: Duration = 5.minutes): Nip46Client {
            val json = Json { ignoreUnknownKeys = true }
            val since = now() - 10
            var last: Exception? = null
            for (url in offer.relays) {
                val session = try { connector.connect(url) } catch (e: Exception) { last = e; continue }
                try {
                    return coroutineScope {
                        val found = async(start = CoroutineStart.UNDISPATCHED) {
                            session.incoming.mapNotNull { text ->
                                runCatching {
                                    val arr = json.parseToJsonElement(text).jsonArray
                                    if (arr[0].jsonPrimitive.content != "EVENT") return@runCatching null
                                    val e = json.decodeFromJsonElement(Event.serializer(), arr[2])
                                    if (e.kind != KIND || !e.isValid()) return@runCatching null
                                    val key = Nip44.conversationKey(offer.clientKeys.secretKey, e.pubkey.hexToBytes())
                                    val o = json.parseToJsonElement(Nip44.decrypt(e.content, key)).jsonObject
                                    e.pubkey.takeIf { (o["result"] as? JsonPrimitive)?.contentOrNull == offer.secret }
                                }.getOrNull()
                            }.first()
                        }
                        session.send(buildJsonArray {
                            add(JsonPrimitive("REQ")); add(JsonPrimitive(SUB))
                            add(buildJsonObject {
                                putJsonArray("kinds") { add(JsonPrimitive(KIND)) }
                                putJsonArray("#p") { add(JsonPrimitive(offer.clientKeys.publicKeyHex)) }
                                put("since", since)
                            })
                        }.toString())
                        val signer = withTimeout(wait) { found.await() }
                        Nip46Client(offer.clientKeys, signer, offer.relays, connector, now)
                    }
                } finally {
                    runCatching { session.close() }
                }
            }
            throw Nip46Exception("no relay reachable" + (last?.message?.let { ": $it" } ?: ""))
        }
    }
}

/**
 * Signs through a remote signer ("bunker", NIP-46) — the one way to an
 * external signer on iOS (ADR 0006). What comes back is checked as from
 * Amber: the listener's key, the event asked for, a valid signature.
 */
class BunkerSigner(
    override val pubkey: String,
    private val client: Nip46Client,
    /** False: in the background, where the listener cannot answer the signer — it is not
     *  asked at all ([SignerNeedsListener]), as Amber is not opened (security audit 2026-10-22, M-08). */
    private val interactive: Boolean = true,
    /** After each request — the confirmation page it may have shown is done with. */
    private val onDone: () -> Unit = {},
) : NostrSigner {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun <T> asking(block: suspend () -> T): T {
        if (!interactive) throw SignerNeedsListener()
        try { return block() } finally { onDone() }
    }

    override suspend fun sign(event: UnsignedEvent): Event = asking {
        val signed = runCatching { json.decodeFromString(Event.serializer(), client.signEvent(event)) }
            .getOrElse { if (it is Nip46Exception) throw it else throw Nip46Exception("the signer returned no event") }
        val expected = Event.computeId(pubkey, event.createdAt, event.kind, event.tags, event.content)
        if (signed.id != expected || signed.pubkey != pubkey || !signed.isValid()) throw Nip46Exception("the signer returned another event")
        signed
    }

    override suspend fun encryptToSelf(plaintext: String): String = asking {
        client.nip44(true, pubkey, plaintext).takeIf { it.isNotBlank() } ?: throw Nip46Exception("the signer returned nothing")
    }

    override suspend fun decryptFromSelf(payload: String): String = asking { client.nip44(false, pubkey, payload) }
}
