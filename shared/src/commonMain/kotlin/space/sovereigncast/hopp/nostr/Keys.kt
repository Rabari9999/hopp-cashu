package space.sovereigncast.hopp.nostr

import space.sovereigncast.hopp.crypto.Secp256k1
import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.crypto.secureRandomBytes
import space.sovereigncast.hopp.crypto.toHex

/** A Nostr key pair: 32-byte secret, x-only public key (BIP-340). */
class KeyPair(val secretKey: ByteArray) {
    init {
        require(secretKey.size == 32 && Secp256k1.secKeyVerify(secretKey)) { "invalid secret key" }
    }

    /** x-only public key, 32 bytes. */
    val publicKey: ByteArray = Secp256k1.xOnlyPublicKey(secretKey)
    val publicKeyHex: String get() = publicKey.toHex()

    fun signSchnorr(message32: ByteArray): ByteArray = Secp256k1.signSchnorr(message32, secretKey, secureRandomBytes(32))

    companion object {
        fun fromHex(hex: String) = KeyPair(hex.hexToBytes())
        fun generate(): KeyPair {
            while (true) {
                val candidate = secureRandomBytes(32)
                if (Secp256k1.secKeyVerify(candidate)) return KeyPair(candidate)
            }
        }
    }
}

fun verifySchnorr(signature: ByteArray, message32: ByteArray, xOnlyPublicKey: ByteArray): Boolean =
    runCatching { Secp256k1.verifySchnorr(signature, message32, xOnlyPublicKey) }.getOrDefault(false)

/** ECDH x-coordinate (unhashed) between our secret and an x-only public key —
 *  the shared secret NIP-04 and NIP-44 both start from. */
internal fun sharedX(secretKey: ByteArray, xOnlyPublicKey: ByteArray): ByteArray = Secp256k1.sharedX(secretKey, xOnlyPublicKey)
