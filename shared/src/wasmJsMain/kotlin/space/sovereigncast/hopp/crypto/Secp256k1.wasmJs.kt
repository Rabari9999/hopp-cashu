package space.sovereigncast.hopp.crypto

private fun noPrehash(): JsAny = js("({ prehash: false })")

private fun bigInt(hex: String): JsAny = js("BigInt('0x' + hex)")

private fun noblePoint(bytes: ByteArray): NoblePoint = secp256k1.Point.fromBytes(bytes.toUint8Array())

/** noble's toBytes asserts validity, so a sum at infinity throws here. */
private fun NoblePoint.compressed(): ByteArray = toBytes(true).toByteArray()

/** A JS error reaches Kotlin/Wasm as a JsException, which is no kotlin.Exception:
 *  `catch (e: Exception)` around Nostr code would miss it. Thrown as the C library would. */
private inline fun <T> noble(block: () -> T): T =
    try { block() } catch (e: JsException) { throw IllegalArgumentException("secp256k1: ${e.message}") }

internal actual val Secp256k1: Secp256k1Ops = object : Secp256k1Ops {
    override fun secKeyVerify(secretKey: ByteArray): Boolean =
        secretKey.size == 32 && noble { secp256k1.utils.isValidSecretKey(secretKey.toUint8Array()) }

    override fun xOnlyPublicKey(secretKey: ByteArray): ByteArray =
        noble { schnorr.getPublicKey(secretKey.toUint8Array()) }.toByteArray()

    override fun signSchnorr(message32: ByteArray, secretKey: ByteArray, auxRand32: ByteArray): ByteArray =
        noble { schnorr.sign(message32.toUint8Array(), secretKey.toUint8Array(), auxRand32.toUint8Array()) }.toByteArray()

    override fun verifySchnorr(signature: ByteArray, message32: ByteArray, xOnlyPublicKey: ByteArray): Boolean =
        noble { schnorr.verify(signature.toUint8Array(), message32.toUint8Array(), xOnlyPublicKey.toUint8Array()) }

    override fun sharedX(secretKey: ByteArray, xOnlyPublicKey: ByteArray): ByteArray {
        val point = noble { secp256k1.getSharedSecret(secretKey.toUint8Array(), (byteArrayOf(0x02) + xOnlyPublicKey).toUint8Array(), true) }
        return point.toByteArray().copyOfRange(1, 33)
    }

    // noble's "recovered" format puts the recovery byte first.
    override fun ecdsaRecoverCompressed(signature64: ByteArray, message32: ByteArray, recoveryId: Int): ByteArray =
        noble { secp256k1.recoverPublicKey((byteArrayOf(recoveryId.toByte()) + signature64).toUint8Array(), message32.toUint8Array(), noPrehash()) }.toByteArray()

    override fun publicKeyCompressed(secretKey: ByteArray): ByteArray =
        noble { secp256k1.Point.BASE.multiply(bigInt(secretKey.toHex())).compressed() }

    override fun pointAdd(a: ByteArray, b: ByteArray): ByteArray = noble { noblePoint(a).add(noblePoint(b)).compressed() }

    override fun pointMul(point: ByteArray, scalar: ByteArray): ByteArray =
        noble { noblePoint(point).multiply(bigInt(scalar.toHex())).compressed() }

    override fun pointNegate(point: ByteArray): ByteArray = noble { noblePoint(point).negate().compressed() }

    override fun pointUncompressed(point: ByteArray): ByteArray = noble { noblePoint(point).toBytes(false).toByteArray() }
}
