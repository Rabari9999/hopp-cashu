package space.sovereigncast.hopp.value

import fr.acinq.secp256k1.Secp256k1
import space.sovereigncast.hopp.crypto.Sha256

/**
 * The parts of a BOLT11 invoice Hopp must check before paying: the amount
 * (ADR 0005: pay only if it is exactly what the listener chose) and the
 * description hash (LUD-06: it must commit to the LNURL metadata).
 * Not a full decoder — signature and routing hints are the wallet's business.
 */
data class Bolt11(
    val network: String,
    val amountMsat: Long?,
    val paymentHash: String?,
    val descriptionHashHex: String?,
    /** Who gets paid: the `n` field, else recovered from the signature
     *  (BOLT 11) — null if neither works. */
    val payeeHex: String? = null,
) {
    companion object {
        private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

        fun parse(invoice: String): Bolt11 {
            val raw = invoice.trim().removePrefix("lightning:").removePrefix("LIGHTNING:")
            require(raw == raw.lowercase() || raw == raw.uppercase()) { "mixed case" }
            val s = raw.lowercase()
            val sep = s.lastIndexOf('1')
            require(sep > 2 && s.startsWith("ln")) { "not a BOLT11 invoice" }
            val hrp = s.substring(0, sep)
            val data = s.substring(sep + 1).map { c -> CHARSET.indexOf(c).also { require(it >= 0) { "invalid character" } } }
            require(verifyChecksum(hrp, data)) { "invalid checksum" }
            require(data.size >= 7 + 104 + 6) { "too short" }

            val m = Regex("^ln(bc|tb|bcrt|tbs|sb)(\\d+)?([munp])?$").find(hrp) ?: throw IllegalArgumentException("unknown prefix $hrp")
            val (net, digits, mult) = m.destructured
            val amount = digits.takeIf { it.isNotEmpty() }?.let { amountMsat(it.toLong(), mult) }

            val words = data.dropLast(6)
            var i = 7 // 35-bit timestamp
            var paymentHash: String? = null
            var descriptionHash: String? = null
            var payee: String? = null
            while (i + 3 <= words.size - 104) { // last 104 words: signature
                val tag = words[i]
                val len = words[i + 1] * 32 + words[i + 2]
                val field = words.subList(i + 3, minOf(i + 3 + len, words.size))
                when (CHARSET[tag]) {
                    'p' -> if (len == 52) paymentHash = toBytes(field).toHexString()
                    'h' -> if (len == 52) descriptionHash = toBytes(field).toHexString()
                    'n' -> if (len == 53) payee = toBytes(field).toHexString()
                }
                i += 3 + len
            }
            return Bolt11(net, amount, paymentHash, descriptionHash, payee ?: recoverPayee(hrp, words))
        }

        /** The signature signs SHA-256(hrp ‖ data without signature, padded to
         *  whole bytes); the recovery id lets the key be recovered from it. */
        internal fun recoverPayee(hrp: String, words: List<Int>): String? = runCatching {
            val signature = toBytes(words.takeLast(104))
            val signed = hrp.encodeToByteArray() + toBytesPadded(words.dropLast(104))
            val full = Secp256k1.ecdsaRecover(signature.copyOfRange(0, 64), Sha256.digest(signed), signature[64].toInt())
            Secp256k1.pubKeyCompress(full).toHexString()
        }.getOrNull()

        /** For tests: the payee from the signature alone, ignoring an `n` field. */
        internal fun payeeFromSignature(invoice: String): String? {
            val s = invoice.trim().lowercase()
            val sep = s.lastIndexOf('1')
            return recoverPayee(s.substring(0, sep), s.substring(sep + 1).map { CHARSET.indexOf(it) }.dropLast(6))
        }

        private fun toBytesPadded(words: List<Int>): ByteArray {
            val out = ArrayList<Byte>(); var acc = 0; var bits = 0
            for (w in words) { acc = (acc shl 5) or w; bits += 5; while (bits >= 8) { bits -= 8; out += ((acc shr bits) and 0xFF).toByte() } }
            if (bits > 0) out += ((acc shl (8 - bits)) and 0xFF).toByte()
            return out.toByteArray()
        }

        private fun amountMsat(n: Long, multiplier: String): Long = when (multiplier) {
            "m" -> n * 100_000_000
            "u" -> n * 100_000
            "n" -> n * 100
            "p" -> { require(n % 10 == 0L) { "sub-millisatoshi amount" }; n / 10 }
            else -> n * 100_000_000_000
        }

        private fun toBytes(words: List<Int>): ByteArray {
            val out = ArrayList<Byte>(); var acc = 0; var bits = 0
            for (w in words) { acc = (acc shl 5) or w; bits += 5; while (bits >= 8) { bits -= 8; out += (acc shr bits and 0xFF).toByte() } }
            return out.toByteArray()
        }

        private fun ByteArray.toHexString() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

        private fun polymod(values: List<Int>): Int {
            val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
            var chk = 1
            for (v in values) {
                val top = chk ushr 25
                chk = (chk and 0x1ffffff shl 5) xor v
                for (i in 0 until 5) if ((top ushr i) and 1 == 1) chk = chk xor gen[i]
            }
            return chk
        }

        private fun verifyChecksum(hrp: String, data: List<Int>): Boolean {
            val expanded = hrp.map { it.code ushr 5 } + 0 + hrp.map { it.code and 31 }
            return polymod(expanded + data) == 1
        }
    }
}
