package space.sovereigncast.hopp.nostr

import kotlinx.serialization.Serializable
import space.sovereigncast.hopp.crypto.Sha256
import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.crypto.toHex

/** A Nostr event (NIP-01). */
@Serializable
data class Event(
    val id: String,
    val pubkey: String,
    val created_at: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun tag(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    /** Id matches content and the signature matches the id. */
    fun isValid(): Boolean =
        id == computeId(pubkey, created_at, kind, tags, content) &&
            verifySchnorr(sig.hexToBytes(), id.hexToBytes(), pubkey.hexToBytes())

    companion object {
        fun sign(keys: KeyPair, kind: Int, content: String, tags: List<List<String>> = emptyList(), createdAt: Long): Event {
            val pubkey = keys.publicKeyHex
            val id = computeId(pubkey, createdAt, kind, tags, content)
            return Event(id, pubkey, createdAt, kind, tags, content, keys.signSchnorr(id.hexToBytes()).toHex())
        }

        fun computeId(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
            val serialized = buildString {
                append("[0,\"").append(pubkey).append("\",").append(createdAt).append(',').append(kind).append(",[")
                tags.forEachIndexed { i, tag ->
                    if (i > 0) append(',')
                    append('[')
                    tag.forEachIndexed { j, v -> if (j > 0) append(','); appendJsonString(v) }
                    append(']')
                }
                append("],")
                appendJsonString(content)
                append(']')
            }
            return Sha256.digest(serialized.encodeToByteArray()).toHex()
        }

        /** NIP-01's exact escaping: only these characters are escaped,
         *  everything else is written as UTF-8 — any other serialiser that
         *  escapes more produces a different id. */
        private fun StringBuilder.appendJsonString(s: String) {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> append(c)
            }
            append('"')
        }
    }
}
