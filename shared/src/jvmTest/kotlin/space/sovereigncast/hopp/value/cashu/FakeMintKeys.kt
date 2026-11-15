package space.sovereigncast.hopp.value.cashu

import space.sovereigncast.hopp.crypto.Secp256k1
import space.sovereigncast.hopp.crypto.Sha256
import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.crypto.toHex
import java.math.BigInteger

/** A mint's signing side for tests: a keyset of known private keys that signs with a DLEQ proof (NUT-12). */
class FakeMintKeys(seed: String = "fake mint", maxPower: Int = 20) {
    private val n = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val privateKeys: Map<Long, ByteArray> =
        (0..maxPower).associate { (1L shl it) to Sha256.digest("$seed/${1L shl it}".encodeToByteArray()) }
    val keys: Map<String, String> = privateKeys.entries.associate { (amount, k) -> amount.toString() to Secp256k1.publicKeyCompressed(k).toHex() }
    val id: String = KeysetId.v1(keys)

    private fun scalar(b: ByteArray) = BigInteger(1, b)
    private fun bytes(x: BigInteger) = x.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }

    /** C_ = k·B_ with its DLEQ; [badDleq] breaks the proof. */
    fun sign(message: BlindedMessage, amount: Long = message.amount, badDleq: Boolean = false): BlindSignature {
        val a = privateKeys.getValue(amount)
        val b = message.b.hexToBytes()
        val c = Secp256k1.pointMul(b, a)
        val nonce = Sha256.digest(a + b)
        val r1 = Secp256k1.publicKeyCompressed(nonce)
        val r2 = Secp256k1.pointMul(b, nonce)
        val e = Bdhke.hashE(r1, r2, Secp256k1.publicKeyCompressed(a), c)
        var s = scalar(nonce).add(scalar(e).multiply(scalar(a))).mod(n)
        if (badDleq) s = s.add(BigInteger.ONE).mod(n)
        return BlindSignature(amount, id, c.toHex(), BlindSignatureDleq(e.toHex(), bytes(s).toHex()))
    }

    /** What a mint checks when a proof comes back: C = k·hash_to_curve(secret). */
    fun accepts(proof: Proof): Boolean = proof.id == id &&
        Secp256k1.pointMul(Bdhke.hashToCurve(proof.secret.encodeToByteArray()), privateKeys[proof.amount] ?: return false).toHex() == proof.c
}
