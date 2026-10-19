package space.sovereigncast.hopp.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The listener said no in the signer. */
class SignerRejected : Exception("the signer refused")

/** The signer could not answer: not installed, crashed, or gave back nothing usable. */
class SignerUnavailable(message: String) : Exception(message)

/** What Hopp asks the signer to allow without asking each time (NIP-55 `permissions`). */
data class SignerPermission(val type: String, val kind: Int? = null)

/** What a signer gives back for `sign_event`: the signed event, or only the signature. */
data class SignerResult(val eventJson: String?, val signature: String?)

/**
 * A signer app on the device (NIP-55: Amber and others) — the key stays in it,
 * Hopp only ever sees signatures (ADR 0006/0021, stage 1b). Android only; on
 * other platforms there is none.
 */
interface ExternalSigner {
    /** Whether a signer app is installed. */
    fun available(): Boolean

    /** Opens the signer to log in: (pubkey hex, the signer's package). */
    suspend fun login(permissions: List<SignerPermission>): Pair<String, String>

    suspend fun signEvent(signerPackage: String, eventJson: String, eventId: String, currentUser: String): SignerResult

    companion object {
        /** Hopp's requests the listener can allow once: NIP-98 for votes and suggestions. */
        /** NIP-98 for Sovereigncast; comments, answers (1, 1111), likes (7), the mute list (10000)
         *  and reports (1984) since stage 3 — the signer app lets the listener choose. */
        val SIGNED_KINDS = listOf(Nip98.KIND, 1, 1111, 7, 10000, 1984, 10002)
        val PERMISSIONS = SIGNED_KINDS.map { SignerPermission("sign_event", it) }

        fun permissionsJson(permissions: List<SignerPermission>): String = buildJsonArray {
            permissions.forEach { p -> add(buildJsonObject { put("type", p.type); p.kind?.let { put("kind", it) } }) }
        }.toString()
    }
}

/**
 * Signs through an [ExternalSigner]. What comes back is checked as if it came
 * from a stranger: the listener's key, the event Hopp asked for, a valid
 * signature — a signer app is someone else's code.
 */
class ExternalNostrSigner(
    override val pubkey: String,
    private val signerPackage: String,
    private val signer: ExternalSigner,
) : NostrSigner {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun sign(event: UnsignedEvent): Event {
        val id = Event.computeId(pubkey, event.createdAt, event.kind, event.tags, event.content)
        val unsigned = buildJsonObject {
            put("id", id); put("pubkey", pubkey); put("created_at", event.createdAt); put("kind", event.kind)
            put("tags", JsonArray(event.tags.map { tag -> JsonArray(tag.map(::JsonPrimitive)) }))
            put("content", event.content)
        }.toString()
        val result = signer.signEvent(signerPackage, unsigned, id, pubkey)
        val signed = result.eventJson?.let { runCatching { json.decodeFromString(Event.serializer(), it) }.getOrNull() }
            ?: result.signature?.let { Event(id, pubkey, event.createdAt, event.kind, event.tags, event.content, it) }
            ?: throw SignerUnavailable("the signer returned nothing")
        if (signed.id != id || signed.pubkey != pubkey || !signed.isValid()) throw SignerUnavailable("the signer returned another event")
        return signed
    }
}
