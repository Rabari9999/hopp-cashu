package space.sovereigncast.hopp.nostr

import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.crypto.toHex

/** A note as NIP-19 names it: its id, and from an `nevent` the relays that have it and its author. */
data class NoteRef(val id: String, val relays: List<String> = emptyList(), val author: String? = null)

/**
 * NIP-19's `note1…` and `nevent1…`, with or without `nostr:` (NIP-21) — how a
 * feed's `<podcast:socialInteract>` and Sovereigncast's episode notes name a
 * show's note. Null for anything else.
 */
fun noteRef(text: String): NoteRef? = runCatching {
    val (hrp, data) = Bech32.decode(text.trim().removePrefix("nostr:"))
    when (hrp) {
        "note" -> data.takeIf { it.size == 32 }?.let { NoteRef(it.toHex()) }
        "nevent" -> {
            val tlv = tlv(data)
            val id = tlv.firstOrNull { it.first == 0 }?.second?.takeIf { it.size == 32 }?.toHex() ?: return@runCatching null
            val relays = tlv.filter { it.first == 1 }.map { it.second.decodeToString().trim().trimEnd('/') }
                .filter { it.startsWith("wss://") || it.startsWith("ws://") }.distinct()
            NoteRef(id, relays, tlv.firstOrNull { it.first == 2 }?.second?.takeIf { it.size == 32 }?.toHex())
        }
        else -> null
    }
}.getOrNull()

/** [ref] as an `nevent1…` (NIP-19): its id, its first relay, its author, its [kind]. */
fun nevent(ref: NoteRef, kind: Int = 1): String {
    val out = mutableListOf<Byte>()
    fun put(type: Int, value: ByteArray) { out += type.toByte(); out += value.size.toByte(); out += value.toList() }
    put(0, ref.id.hexToBytes())
    ref.relays.firstOrNull()?.let { put(1, it.encodeToByteArray()) }
    ref.author?.let { put(2, it.hexToBytes()) }
    put(3, byteArrayOf((kind ushr 24).toByte(), (kind ushr 16).toByte(), (kind ushr 8).toByte(), kind.toByte()))
    return Bech32.encode("nevent", out.toByteArray())
}

/** The hex key of an `npub1…` or `nprofile1…`, with or without `nostr:`; null otherwise. */
fun profileKey(text: String): String? = runCatching {
    val (hrp, data) = Bech32.decode(text.trim().removePrefix("nostr:"))
    when (hrp) {
        "npub" -> data.takeIf { it.size == 32 }?.toHex()
        "nprofile" -> tlv(data).firstOrNull { it.first == 0 }?.second?.takeIf { it.size == 32 }?.toHex()
        else -> null
    }
}.getOrNull()

/** NIP-19's type-length-value list; a cut-off entry ends it. */
private fun tlv(data: ByteArray): List<Pair<Int, ByteArray>> {
    val out = mutableListOf<Pair<Int, ByteArray>>()
    var i = 0
    while (i + 2 <= data.size) {
        val type = data[i].toInt() and 0xff
        val length = data[i + 1].toInt() and 0xff
        if (i + 2 + length > data.size) break
        out += type to data.copyOfRange(i + 2, i + 2 + length)
        i += 2 + length
    }
    return out
}
