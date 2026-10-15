package space.sovereigncast.hopp.crypto

/**
 * scrypt (RFC 7914) — NIP-49's password hash. Memory is 128·r·N bytes: at
 * NIP-49's log_n 16 that is 64 MiB, for a second or two; Hopp runs it only
 * when a key is exported or imported, off the main thread.
 */
object Scrypt {
    fun derive(password: ByteArray, salt: ByteArray, n: Int, r: Int, p: Int, dkLen: Int): ByteArray {
        require(n > 1 && n and (n - 1) == 0) { "N must be a power of two" }
        val blockSize = 128 * r
        val b = pbkdf2(password, salt, p * blockSize)
        val xy = IntArray(64 * r)
        val v = IntArray(32 * r * n)
        for (i in 0 until p) roMix(b, i * blockSize, r, n, v, xy)
        return pbkdf2(password, b, dkLen)
    }

    /** PBKDF2-HMAC-SHA256 with one iteration, as scrypt uses it. */
    private fun pbkdf2(password: ByteArray, salt: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var block = 1
        var pos = 0
        while (pos < length) {
            val u = hmacSha256(password, salt + byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            val n = minOf(32, length - pos)
            u.copyInto(out, pos, 0, n)
            pos += n; block++
        }
        return out
    }

    private fun roMix(b: ByteArray, offset: Int, r: Int, n: Int, v: IntArray, xy: IntArray) {
        val words = 32 * r
        val x = IntArray(words)
        for (i in 0 until words) {
            val o = offset + 4 * i
            x[i] = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
        }
        for (i in 0 until n) { x.copyInto(v, i * words); blockMix(x, r, xy) }
        for (i in 0 until n) {
            val j = x[words - 16] and (n - 1)
            for (k in 0 until words) x[k] = x[k] xor v[j * words + k]
            blockMix(x, r, xy)
        }
        for (i in 0 until words) {
            val o = offset + 4 * i
            b[o] = x[i].toByte(); b[o + 1] = (x[i] ushr 8).toByte(); b[o + 2] = (x[i] ushr 16).toByte(); b[o + 3] = (x[i] ushr 24).toByte()
        }
    }

    private fun blockMix(b: IntArray, r: Int, y: IntArray) {
        val t = IntArray(16)
        b.copyInto(t, 0, (2 * r - 1) * 16, 2 * r * 16)
        for (i in 0 until 2 * r) {
            for (k in 0 until 16) t[k] = t[k] xor b[i * 16 + k]
            salsa8(t)
            // Even blocks to the first half, odd to the second.
            t.copyInto(y, ((i / 2) + (i % 2) * r) * 16)
        }
        y.copyInto(b, 0, 0, 32 * r)
    }

    private fun salsa8(b: IntArray) {
        val x = b.copyOf()
        repeat(4) {
            x[4] = x[4] xor (x[0] + x[12]).rotateLeft(7); x[8] = x[8] xor (x[4] + x[0]).rotateLeft(9)
            x[12] = x[12] xor (x[8] + x[4]).rotateLeft(13); x[0] = x[0] xor (x[12] + x[8]).rotateLeft(18)
            x[9] = x[9] xor (x[5] + x[1]).rotateLeft(7); x[13] = x[13] xor (x[9] + x[5]).rotateLeft(9)
            x[1] = x[1] xor (x[13] + x[9]).rotateLeft(13); x[5] = x[5] xor (x[1] + x[13]).rotateLeft(18)
            x[14] = x[14] xor (x[10] + x[6]).rotateLeft(7); x[2] = x[2] xor (x[14] + x[10]).rotateLeft(9)
            x[6] = x[6] xor (x[2] + x[14]).rotateLeft(13); x[10] = x[10] xor (x[6] + x[2]).rotateLeft(18)
            x[3] = x[3] xor (x[15] + x[11]).rotateLeft(7); x[7] = x[7] xor (x[3] + x[15]).rotateLeft(9)
            x[11] = x[11] xor (x[7] + x[3]).rotateLeft(13); x[15] = x[15] xor (x[11] + x[7]).rotateLeft(18)
            x[1] = x[1] xor (x[0] + x[3]).rotateLeft(7); x[2] = x[2] xor (x[1] + x[0]).rotateLeft(9)
            x[3] = x[3] xor (x[2] + x[1]).rotateLeft(13); x[0] = x[0] xor (x[3] + x[2]).rotateLeft(18)
            x[6] = x[6] xor (x[5] + x[4]).rotateLeft(7); x[7] = x[7] xor (x[6] + x[5]).rotateLeft(9)
            x[4] = x[4] xor (x[7] + x[6]).rotateLeft(13); x[5] = x[5] xor (x[4] + x[7]).rotateLeft(18)
            x[11] = x[11] xor (x[10] + x[9]).rotateLeft(7); x[8] = x[8] xor (x[11] + x[10]).rotateLeft(9)
            x[9] = x[9] xor (x[8] + x[11]).rotateLeft(13); x[10] = x[10] xor (x[9] + x[8]).rotateLeft(18)
            x[12] = x[12] xor (x[15] + x[14]).rotateLeft(7); x[13] = x[13] xor (x[12] + x[15]).rotateLeft(9)
            x[14] = x[14] xor (x[13] + x[12]).rotateLeft(13); x[15] = x[15] xor (x[14] + x[13]).rotateLeft(18)
        }
        for (i in 0 until 16) b[i] += x[i]
    }
}
