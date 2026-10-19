package space.sovereigncast.hopp.nostr

import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import space.sovereigncast.hopp.crypto.Sha256
import space.sovereigncast.hopp.crypto.toHex

/** An event before it is signed: who signs it decides the pubkey and the id. */
data class UnsignedEvent(val kind: Int, val content: String, val tags: List<List<String>> = emptyList(), val createdAt: Long)

/**
 * Who signs for the listener (ADR 0006/0021): a key Hopp made and keeps, or —
 * in later steps — Amber (NIP-55) or a remote signer (NIP-46). Everything
 * above this interface does not know which.
 */
interface NostrSigner {
    /** x-only public key, hex. */
    val pubkey: String
    suspend fun sign(event: UnsignedEvent): Event
}

/** A key Hopp holds itself (in the platform's secret store). */
class LocalSigner(private val keys: KeyPair) : NostrSigner {
    override val pubkey: String = keys.publicKeyHex
    override suspend fun sign(event: UnsignedEvent): Event =
        Event.sign(keys, event.kind, event.content, event.tags, event.createdAt)

    /** The key a private zap on [id] at [createdAt] is sent with, as Amethyst derives it —
     *  so the listener's key, in Amethyst too, can open it again. */
    fun privateZapKey(id: String, createdAt: Long): KeyPair =
        KeyPair(space.sovereigncast.hopp.crypto.Sha256.digest((keys.secretKey.toHex() + id + createdAt).encodeToByteArray()))
}

/**
 * NIP-98 HTTP auth: `Authorization: Nostr <base64 of a signed kind-27235
 * event>` naming the exact URL, the method and — for a body — its SHA-256,
 * so the signature cannot be reused for another request.
 */
object Nip98 {
    const val KIND = 27235

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun authorization(signer: NostrSigner, url: String, method: String, body: ByteArray?, now: Long): String {
        val tags = buildList {
            add(listOf("u", url))
            add(listOf("method", method.uppercase()))
            if (body != null && body.isNotEmpty()) add(listOf("payload", Sha256.digest(body).toHex()))
        }
        val event = signer.sign(UnsignedEvent(KIND, "", tags, now))
        return "Nostr " + Base64.encode(Json.encodeToString(Event.serializer(), event).encodeToByteArray())
    }
}
