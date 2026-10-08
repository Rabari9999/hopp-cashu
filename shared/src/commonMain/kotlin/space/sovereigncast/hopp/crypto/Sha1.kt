package space.sovereigncast.hopp.crypto

/**
 * SHA-1 (FIPS 180-4) — only for the Podcast Index request signature, which
 * the API defines that way (ADR 0008). Nothing that has to be secure uses it.
 */
object Sha1 {
    fun digest(message: ByteArray): ByteArray {
        var h0 = 0x67452301; var h1 = 0xEFCDAB89.toInt(); var h2 = 0x98BADCFE.toInt(); var h3 = 0x10325476; var h4 = 0xC3D2E1F0.toInt()
        val bitLength = message.size.toLong() * 8
        val padded = ByteArray(((message.size + 9 + 63) / 64) * 64)
        message.copyInto(padded)
        padded[message.size] = 0x80.toByte()
        for (i in 0 until 8) padded[padded.size - 1 - i] = (bitLength ushr (8 * i)).toByte()
        val w = IntArray(80)
        for (chunk in padded.indices step 64) {
            for (i in 0 until 16) {
                val j = chunk + i * 4
                w[i] = (padded[j].toInt() and 0xFF shl 24) or (padded[j + 1].toInt() and 0xFF shl 16) or
                    (padded[j + 2].toInt() and 0xFF shl 8) or (padded[j + 3].toInt() and 0xFF)
            }
            for (i in 16 until 80) w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
            var a = h0; var b = h1; var c = h2; var d = h3; var e = h4
            for (i in 0 until 80) {
                val (f, k) = when (i) {
                    in 0..19 -> ((b and c) or (b.inv() and d)) to 0x5A827999
                    in 20..39 -> (b xor c xor d) to 0x6ED9EBA1
                    in 40..59 -> ((b and c) or (b and d) or (c and d)) to 0x8F1BBCDC.toInt()
                    else -> (b xor c xor d) to 0xCA62C1D6.toInt()
                }
                val t = a.rotateLeft(5) + f + e + k + w[i]
                e = d; d = c; c = b.rotateLeft(30); b = a; a = t
            }
            h0 += a; h1 += b; h2 += c; h3 += d; h4 += e
        }
        return intArrayOf(h0, h1, h2, h3, h4).flatMap { v -> (3 downTo 0).map { (v ushr (8 * it)).toByte() } }.toByteArray()
    }
}
