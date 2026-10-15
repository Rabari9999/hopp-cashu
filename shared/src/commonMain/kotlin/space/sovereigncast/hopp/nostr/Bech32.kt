package space.sovereigncast.hopp.nostr

import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.crypto.toHex

/**
 * Bech32 (BIP-173) as Nostr uses it (NIP-19 npub/nsec/note, NIP-49
 * ncryptsec) — without BIP-173's 90-character limit, which an ncryptsec
 * exceeds.
 */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    fun encode(hrp: String, data: ByteArray): String {
        val values = convertBits(data, 8, 5, pad = true)
        val checksum = checksum(hrp, values)
        return hrp + "1" + (values + checksum).joinToString("") { CHARSET[it].toString() }
    }

    /** (hrp, bytes), or an exception for anything that is not valid bech32. */
    fun decode(text: String): Pair<String, ByteArray> {
        val s = text.trim()
        require(s == s.lowercase() || s == s.uppercase()) { "mixed case" }
        val lower = s.lowercase()
        val sep = lower.lastIndexOf('1')
        require(sep >= 1 && sep + 7 <= lower.length) { "no separator" }
        val hrp = lower.substring(0, sep)
        val values = lower.substring(sep + 1).map { c -> CHARSET.indexOf(c).also { require(it >= 0) { "invalid character" } } }
        require(polymod(expand(hrp) + values) == 1) { "bad checksum" }
        return hrp to convertBits(values.dropLast(6).map { it.toByte() }.toByteArray(), 5, 8, pad = false).map { it.toByte() }.toByteArray()
    }

    private fun polymod(values: List<Int>): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((top ushr i) and 1 == 1) chk = chk xor GEN[i]
        }
        return chk
    }

    private fun expand(hrp: String) = hrp.map { it.code ushr 5 } + 0 + hrp.map { it.code and 31 }

    private fun checksum(hrp: String, values: List<Int>): List<Int> {
        val mod = polymod(expand(hrp) + values + List(6) { 0 }) xor 1
        return List(6) { (mod ushr (5 * (5 - it))) and 31 }
    }

    private fun convertBits(data: ByteArray, from: Int, to: Int, pad: Boolean): List<Int> {
        var acc = 0; var bits = 0
        val out = mutableListOf<Int>()
        val max = (1 shl to) - 1
        for (b in data) {
            acc = (acc shl from) or (b.toInt() and ((1 shl from) - 1))
            bits += from
            while (bits >= to) { bits -= to; out += (acc ushr bits) and max }
        }
        if (pad) { if (bits > 0) out += (acc shl (to - bits)) and max }
        else require(bits < from && (acc shl (to - bits)) and max == 0) { "invalid padding" }
        return out
    }
}

/** NIP-19 for a 32-byte key in hex. */
fun npub(pubkeyHex: String): String = Bech32.encode("npub", pubkeyHex.hexToBytes())

/** The hex key of an npub, or null. */
fun npubToHex(npub: String): String? = runCatching {
    val (hrp, data) = Bech32.decode(npub.removePrefix("nostr:"))
    data.takeIf { hrp == "npub" && it.size == 32 }?.toHex()
}.getOrNull()
