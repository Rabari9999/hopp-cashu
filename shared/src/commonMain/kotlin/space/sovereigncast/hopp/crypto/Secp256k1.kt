package space.sovereigncast.hopp.crypto

/**
 * The secp256k1 operations Hopp needs — BIP-340 for Nostr, ECDH for NIP-04 and
 * NIP-44, ECDSA recovery for a BOLT 11 payee, point arithmetic for Cashu's blind
 * signatures (NUT-00, NUT-12). ACINQ's secp256k1-kmp (the C
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

    // Point arithmetic. Points are 33-byte compressed public keys (65-byte uncompressed
    // ones are accepted too), scalars 32 bytes in [1, n). Results come back compressed.
    // A malformed point or scalar, or a result at infinity, throws IllegalArgumentException.

    /** secretKey · G, compressed (33 bytes). */
    fun publicKeyCompressed(secretKey: ByteArray): ByteArray

    /** a + b. */
    fun pointAdd(a: ByteArray, b: ByteArray): ByteArray

    /** scalar · point. */
    fun pointMul(point: ByteArray, scalar: ByteArray): ByteArray

    /** −point. */
    fun pointNegate(point: ByteArray): ByteArray

    /** The 65-byte uncompressed encoding; also the check that [point] is on the curve. */
    fun pointUncompressed(point: ByteArray): ByteArray
}

internal expect val Secp256k1: Secp256k1Ops
