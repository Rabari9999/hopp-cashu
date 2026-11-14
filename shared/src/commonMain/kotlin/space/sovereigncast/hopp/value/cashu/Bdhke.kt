package space.sovereigncast.hopp.value.cashu

import space.sovereigncast.hopp.crypto.Secp256k1
import space.sovereigncast.hopp.crypto.Sha256
import space.sovereigncast.hopp.crypto.toHex

/**
 * Blind Diffie-Hellman key exchange, the blind signature behind Cashu ecash
 * (NUT-00), and the wallet's side of the mint's DLEQ proof (NUT-12).
 *
 * The wallet picks a secret x and a blinding factor r and sends B_ = Y + r·G,
 * Y = hash_to_curve(x). The mint signs with its key k for that amount,
 * C_ = k·B_, and the wallet unblinds C = C_ − r·K = k·Y. The mint never sees
 * x or C, so it cannot link the proof (x, C) it later redeems to the signing.
 *
 * Points are 33-byte compressed public keys, scalars 32 bytes.
 */
internal object Bdhke {
    private val DOMAIN = "Secp256k1_HashToCurve_Cashu_".encodeToByteArray()

    /** Y: the first counter whose hash is the x coordinate of an even-y curve point. */
    fun hashToCurve(message: ByteArray): ByteArray {
        val messageHash = Sha256.digest(DOMAIN + message)
        for (counter in 0 until 65536) {
            val littleEndian = byteArrayOf(counter.toByte(), (counter ushr 8).toByte(), 0, 0)
            val candidate = byteArrayOf(0x02) + Sha256.digest(messageHash + littleEndian)
            if (isPoint(candidate)) return candidate
        }
        throw IllegalStateException("hash_to_curve: no point in 2^16 tries")
    }

    fun isPoint(bytes: ByteArray): Boolean =
        try { Secp256k1.pointUncompressed(bytes); true } catch (_: IllegalArgumentException) { false }

    /** B_ = Y + r·G. */
    fun blind(secret: ByteArray, r: ByteArray): ByteArray =
        Secp256k1.pointAdd(hashToCurve(secret), Secp256k1.publicKeyCompressed(r))

    /** C = C_ − r·K, K the mint's public key for the amount. */
    fun unblind(blindSignature: ByteArray, r: ByteArray, mintKey: ByteArray): ByteArray =
        Secp256k1.pointAdd(blindSignature, Secp256k1.pointNegate(Secp256k1.pointMul(mintKey, r)))

    /** NUT-12 hash_e: SHA-256 over the lowercase hex of each point, uncompressed, concatenated. */
    fun hashE(vararg points: ByteArray): ByteArray =
        Sha256.digest(points.joinToString("") { Secp256k1.pointUncompressed(it).toHex() }.encodeToByteArray())

    /**
     * Whether C_ was signed with the private key behind [mintKey] A (NUT-12):
     * R1 = s·G − e·A, R2 = s·B_ − e·C_, and e must equal hash_e(R1, R2, A, C_).
     * False for any malformed input; a mint's bad proof is no crash.
     */
    fun verifyDleq(e: ByteArray, s: ByteArray, mintKey: ByteArray, blinded: ByteArray, blindSignature: ByteArray): Boolean =
        try {
            val r1 = Secp256k1.pointAdd(Secp256k1.publicKeyCompressed(s), Secp256k1.pointNegate(Secp256k1.pointMul(mintKey, e)))
            val r2 = Secp256k1.pointAdd(Secp256k1.pointMul(blinded, s), Secp256k1.pointNegate(Secp256k1.pointMul(blindSignature, e)))
            hashE(r1, r2, mintKey, blindSignature).contentEquals(e)
        } catch (_: IllegalArgumentException) {
            false
        }

    /**
     * The same check on a received proof (x, C) that carries its blinding factor r:
     * B_ = Y + r·G and C_ = C + r·A are rebuilt first.
     */
    fun verifyProofDleq(secret: ByteArray, signature: ByteArray, e: ByteArray, s: ByteArray, r: ByteArray, mintKey: ByteArray): Boolean =
        try {
            val blinded = blind(secret, r)
            val blindSignature = Secp256k1.pointAdd(signature, Secp256k1.pointMul(mintKey, r))
            verifyDleq(e, s, mintKey, blinded, blindSignature)
        } catch (_: IllegalArgumentException) {
            false
        }
}
