package space.sovereigncast.hopp.crypto

/**
 * Poly1305 (RFC 8439 §2.5) and the AEAD built from it with XChaCha20 — what
 * NIP-49 encrypts a key with. 26-bit limbs, as the reference implementation.
 */
object Poly1305 {
    fun mac(key: ByteArray, message: ByteArray): ByteArray {
        require(key.size == 32)
        val r0 = le32(key, 0) and 0x3ffffff
        val r1 = (le32(key, 3) ushr 2) and 0x3ffff03
        val r2 = (le32(key, 6) ushr 4) and 0x3ffc0ff
        val r3 = (le32(key, 9) ushr 6) and 0x3f03fff
        val r4 = (le32(key, 12) ushr 8) and 0x00fffff
        val s1 = r1 * 5L; val s2 = r2 * 5L; val s3 = r3 * 5L; val s4 = r4 * 5L
        var h0 = 0L; var h1 = 0L; var h2 = 0L; var h3 = 0L; var h4 = 0L
        val block = ByteArray(17)
        var pos = 0
        while (pos < message.size) {
            val n = minOf(16, message.size - pos)
            block.fill(0)
            message.copyInto(block, 0, pos, pos + n)
            block[n] = 1
            h0 += le32(block, 0) and 0x3ffffff
            h1 += (le32(block, 3) ushr 2) and 0x3ffffff
            h2 += (le32(block, 6) ushr 4) and 0x3ffffff
            h3 += (le32(block, 9) ushr 6) and 0x3ffffff
            h4 += (le32(block, 12) ushr 8) or ((block[16].toLong() and 0xff) shl 24)
            val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
            var d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
            var d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
            var d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
            var d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0
            var c = d0 ushr 26; h0 = d0 and 0x3ffffff
            d1 += c; c = d1 ushr 26; h1 = d1 and 0x3ffffff
            d2 += c; c = d2 ushr 26; h2 = d2 and 0x3ffffff
            d3 += c; c = d3 ushr 26; h3 = d3 and 0x3ffffff
            d4 += c; c = d4 ushr 26; h4 = d4 and 0x3ffffff
            h0 += c * 5; c = h0 ushr 26; h0 = h0 and 0x3ffffff
            h1 += c
            pos += n
        }
        var c = h1 ushr 26; h1 = h1 and 0x3ffffff
        h2 += c; c = h2 ushr 26; h2 = h2 and 0x3ffffff
        h3 += c; c = h3 ushr 26; h3 = h3 and 0x3ffffff
        h4 += c; c = h4 ushr 26; h4 = h4 and 0x3ffffff
        h0 += c * 5; c = h0 ushr 26; h0 = h0 and 0x3ffffff
        h1 += c
        // h - p, and keep h if that goes negative
        var g0 = h0 + 5; c = g0 ushr 26; g0 = g0 and 0x3ffffff
        var g1 = h1 + c; c = g1 ushr 26; g1 = g1 and 0x3ffffff
        var g2 = h2 + c; c = g2 ushr 26; g2 = g2 and 0x3ffffff
        var g3 = h3 + c; c = g3 ushr 26; g3 = g3 and 0x3ffffff
        val g4 = h4 + c - (1L shl 26)
        if (g4 >= 0) { h0 = g0; h1 = g1; h2 = g2; h3 = g3; h4 = g4 }
        val f0 = (h0 or (h1 shl 26)) and 0xffffffffL
        val f1 = ((h1 ushr 6) or (h2 shl 20)) and 0xffffffffL
        val f2 = ((h2 ushr 12) or (h3 shl 14)) and 0xffffffffL
        val f3 = ((h3 ushr 18) or (h4 shl 8)) and 0xffffffffL
        var acc = f0 + le32(key, 16); val o0 = acc
        acc = f1 + le32(key, 20) + (acc ushr 32); val o1 = acc
        acc = f2 + le32(key, 24) + (acc ushr 32); val o2 = acc
        acc = f3 + le32(key, 28) + (acc ushr 32); val o3 = acc
        val out = ByteArray(16)
        for ((i, v) in listOf(o0, o1, o2, o3).withIndex()) for (b in 0 until 4) out[4 * i + b] = (v ushr (8 * b)).toByte()
        return out
    }

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xff) or ((b[o + 1].toLong() and 0xff) shl 8) or
            ((b[o + 2].toLong() and 0xff) shl 16) or ((b[o + 3].toLong() and 0xff) shl 24)
}

class AeadException : Exception("authentication failed")

/** XChaCha20-Poly1305 (draft-irtf-cfrg-xchacha): HChaCha20 of the key and the
 *  nonce's first 16 bytes, then the RFC 8439 AEAD with the last 8. */
object XChaCha20Poly1305 {
    fun encrypt(key: ByteArray, nonce24: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val (subKey, nonce12) = derive(key, nonce24)
        val ciphertext = ChaCha20.xor(subKey, nonce12, plaintext, counter = 1)
        return ciphertext + tag(subKey, nonce12, aad, ciphertext)
    }

    fun decrypt(key: ByteArray, nonce24: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray {
        if (sealed.size < 16) throw AeadException()
        val (subKey, nonce12) = derive(key, nonce24)
        val ciphertext = sealed.copyOfRange(0, sealed.size - 16)
        if (!constantTimeEquals(tag(subKey, nonce12, aad, ciphertext), sealed.copyOfRange(sealed.size - 16, sealed.size))) {
            throw AeadException()
        }
        return ChaCha20.xor(subKey, nonce12, ciphertext, counter = 1)
    }

    private fun derive(key: ByteArray, nonce24: ByteArray): Pair<ByteArray, ByteArray> {
        require(key.size == 32 && nonce24.size == 24)
        val nonce12 = ByteArray(12)
        nonce24.copyInto(nonce12, 4, 16, 24)
        return ChaCha20.hchacha20(key, nonce24.copyOfRange(0, 16)) to nonce12
    }

    private fun tag(key: ByteArray, nonce12: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val polyKey = ChaCha20.xor(key, nonce12, ByteArray(32), counter = 0)
        fun pad(n: Int) = ByteArray((16 - n % 16) % 16)
        fun le64(v: Int) = ByteArray(8) { i -> if (i < 4) (v ushr (8 * i)).toByte() else 0 }
        return Poly1305.mac(polyKey, aad + pad(aad.size) + ciphertext + pad(ciphertext.size) + le64(aad.size) + le64(ciphertext.size))
    }
}
