package space.sovereigncast.hopp.crypto

/**
 * AES-256 in CBC mode with PKCS#7 padding — what NIP-04 and private zaps
 * (NIP-57, as Amethyst writes them) encrypt with. Encryption only: Hopp sends
 * private zaps and never needs to open one. Checked against the JVM's own AES.
 */
object Aes {
    private val SBOX = IntArray(256)

    init {
        // The S-box from GF(2^8) inverses and the affine map (FIPS-197, 5.1.1).
        var p = 1
        var q = 1
        do {
            p = p xor ((p shl 1) and 0xff) xor (if (p and 0x80 != 0) 0x1b else 0)
            q = q xor (q shl 1)
            q = q xor (q shl 2)
            q = q xor (q shl 4)
            q = q and 0xff
            if (q and 0x80 != 0) q = q xor 0x09
            val x = q xor rotl8(q, 1) xor rotl8(q, 2) xor rotl8(q, 3) xor rotl8(q, 4)
            SBOX[p] = (x xor 0x63) and 0xff
        } while (p != 1)
        SBOX[0] = 0x63
    }

    private fun rotl8(x: Int, shift: Int) = ((x shl shift) or (x ushr (8 - shift))) and 0xff

    private fun xtime(x: Int) = ((x shl 1) xor (if (x and 0x80 != 0) 0x1b else 0)) and 0xff

    /** The 15 round keys of AES-256, 16 bytes each, back to back. */
    private fun expandKey(key: ByteArray): IntArray {
        require(key.size == 32) { "AES-256 needs a 32-byte key" }
        val w = IntArray(240)
        for (i in 0 until 32) w[i] = key[i].toInt() and 0xff
        var rcon = 1
        var i = 32
        val t = IntArray(4)
        while (i < 240) {
            for (j in 0 until 4) t[j] = w[i - 4 + j]
            if (i % 32 == 0) {
                val first = t[0]
                t[0] = SBOX[t[1]] xor rcon; t[1] = SBOX[t[2]]; t[2] = SBOX[t[3]]; t[3] = SBOX[first]
                rcon = xtime(rcon)
            } else if (i % 32 == 16) {
                for (j in 0 until 4) t[j] = SBOX[t[j]]
            }
            for (j in 0 until 4) { w[i] = w[i - 32] xor t[j]; i++ }
        }
        return w
    }

    private fun encryptBlock(state: IntArray, w: IntArray) {
        for (i in 0 until 16) state[i] = state[i] xor w[i]
        val tmp = IntArray(16)
        for (round in 1..14) {
            // SubBytes and ShiftRows: row r of column c comes from column c + r.
            for (c in 0 until 4) for (r in 0 until 4) tmp[c * 4 + r] = SBOX[state[((c + r) % 4) * 4 + r]]
            if (round < 14) for (c in 0 until 4) {
                val a0 = tmp[c * 4]; val a1 = tmp[c * 4 + 1]; val a2 = tmp[c * 4 + 2]; val a3 = tmp[c * 4 + 3]
                val all = a0 xor a1 xor a2 xor a3
                tmp[c * 4] = a0 xor all xor xtime(a0 xor a1)
                tmp[c * 4 + 1] = a1 xor all xor xtime(a1 xor a2)
                tmp[c * 4 + 2] = a2 xor all xor xtime(a2 xor a3)
                tmp[c * 4 + 3] = a3 xor all xor xtime(a3 xor a0)
            }
            for (i in 0 until 16) state[i] = tmp[i] xor w[round * 16 + i]
        }
    }

    /** [plaintext] under [key] (32 bytes) and [iv] (16 bytes), padded to whole blocks. */
    fun encryptCbc(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        require(iv.size == 16) { "the IV is 16 bytes" }
        val w = expandKey(key)
        val pad = 16 - plaintext.size % 16
        val padded = plaintext + ByteArray(pad) { pad.toByte() }
        val out = ByteArray(padded.size)
        val state = IntArray(16) { iv[it].toInt() and 0xff }
        for (block in padded.indices step 16) {
            for (i in 0 until 16) state[i] = state[i] xor (padded[block + i].toInt() and 0xff)
            encryptBlock(state, w)
            for (i in 0 until 16) out[block + i] = state[i].toByte()
        }
        return out
    }
}
