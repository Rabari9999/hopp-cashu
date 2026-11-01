package space.sovereigncast.hopp.crypto

/**
 * The secp256k1 operations Hopp needs — BIP-340 for Nostr, ECDH for NIP-04 and
 * NIP-44, ECDSA recovery for a BOLT 11 payee. ACINQ's secp256k1-kmp (the C
 * library) on Android, the JVM and iOS; on the web, which it does not build
 * for, `@noble/curves` (ADR 0022, 8).
 */
internal interface Secp256k1Ops {
    fun secKeyVerify(secretKey: ByteArray): Boolean

    /** x-only public key, 32 bytes. */
    fun xOnlyPublicKey(secretKey: ByteArray): ByteArray

    fun signSchnorr(message32: ByteArray, secretKey: ByteArray, auxRand32: ByteArray): ByteArray

    /** Throws on a malformed key or signature; false only for a valid one that does not match. */
    fun verifySchnorr(signature: ByteArray, message32: ByteArray, xOnlyPublicKey: ByteArray): Boolean

    /** The x coordinate of secretKey · P, P the even-y point of [xOnlyPublicKey]. */
    fun sharedX(secretKey: ByteArray, xOnlyPublicKey: ByteArray): ByteArray

    /** Compressed (33-byte) public key from a 64-byte compact signature and its recovery id. */
    fun ecdsaRecoverCompressed(signature64: ByteArray, message32: ByteArray, recoveryId: Int): ByteArray
}

internal expect val Secp256k1: Secp256k1Ops
