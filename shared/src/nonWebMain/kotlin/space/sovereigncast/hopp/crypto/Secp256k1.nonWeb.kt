package space.sovereigncast.hopp.crypto

import fr.acinq.secp256k1.Secp256k1 as Acinq

internal actual val Secp256k1: Secp256k1Ops = object : Secp256k1Ops {
    override fun secKeyVerify(secretKey: ByteArray): Boolean = Acinq.secKeyVerify(secretKey)

    override fun xOnlyPublicKey(secretKey: ByteArray): ByteArray = Acinq.pubkeyCreate(secretKey).copyOfRange(1, 33)

    override fun signSchnorr(message32: ByteArray, secretKey: ByteArray, auxRand32: ByteArray): ByteArray =
        Acinq.signSchnorr(message32, secretKey, auxRand32)

    override fun verifySchnorr(signature: ByteArray, message32: ByteArray, xOnlyPublicKey: ByteArray): Boolean =
        Acinq.verifySchnorr(signature, message32, xOnlyPublicKey)

    override fun sharedX(secretKey: ByteArray, xOnlyPublicKey: ByteArray): ByteArray {
        val point = Acinq.pubKeyTweakMul(byteArrayOf(0x02) + xOnlyPublicKey, secretKey)
        return Acinq.pubKeyCompress(point).copyOfRange(1, 33)
    }

    override fun ecdsaRecoverCompressed(signature64: ByteArray, message32: ByteArray, recoveryId: Int): ByteArray =
        Acinq.pubKeyCompress(Acinq.ecdsaRecover(signature64, message32, recoveryId))
}
