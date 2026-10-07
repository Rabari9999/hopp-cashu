package space.sovereigncast.hopp.crypto

/** ChaCha20 (RFC 8439) — the stream cipher of NIP-44 v2 (without Poly1305;
 *  NIP-44 authenticates with HMAC-SHA256). */
object ChaCha20 {
    fun xor(key: ByteArray, nonce: ByteArray, input: ByteArray, counter: Int = 0): ByteArray {
        require(key.size == 32 && nonce.size == 12)
        val state = IntArray(16)
        state[0] = 0x61707865; state[1] = 0x3320646e; state[2] = 0x79622d32; state[3] = 0x6b206574
        for (i in 0 until 8) state[4 + i] = le32(key, 4 * i)
        state[12] = counter
        for (i in 0 until 3) state[13 + i] = le32(nonce, 4 * i)
        val out = ByteArray(input.size)
        val block = IntArray(16)
        val stream = ByteArray(64)
        var pos = 0
        while (pos < input.size) {
            state.copyInto(block)
            repeat(10) {
                qr(block, 0, 4, 8, 12); qr(block, 1, 5, 9, 13); qr(block, 2, 6, 10, 14); qr(block, 3, 7, 11, 15)
                qr(block, 0, 5, 10, 15); qr(block, 1, 6, 11, 12); qr(block, 2, 7, 8, 13); qr(block, 3, 4, 9, 14)
            }
            for (i in 0 until 16) {
                val v = block[i] + state[i]
                stream[4 * i] = v.toByte(); stream[4 * i + 1] = (v ushr 8).toByte()
                stream[4 * i + 2] = (v ushr 16).toByte(); stream[4 * i + 3] = (v ushr 24).toByte()
            }
            val n = minOf(64, input.size - pos)
            for (i in 0 until n) out[pos + i] = (input[pos + i].toInt() xor stream[i].toInt()).toByte()
            pos += n
            state[12]++
        }
        return out
    }

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or (b[o + 1].toInt() and 0xFF shl 8) or (b[o + 2].toInt() and 0xFF shl 16) or (b[o + 3].toInt() and 0xFF shl 24)

    private fun qr(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(16)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(12)
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(8)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(7)
    }
}
