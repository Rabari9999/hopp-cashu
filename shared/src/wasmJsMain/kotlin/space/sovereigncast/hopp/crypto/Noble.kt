@file:JsModule("@noble/curves/secp256k1.js")

package space.sovereigncast.hopp.crypto

// @noble/curves (audited, paulmillr) — the named exports of its secp256k1 module.

internal external interface NobleSchnorr : JsAny {
    fun getPublicKey(secretKey: Uint8Array): Uint8Array
    fun sign(message: Uint8Array, secretKey: Uint8Array, auxRand: Uint8Array): Uint8Array
    fun verify(signature: Uint8Array, message: Uint8Array, publicKey: Uint8Array): Boolean
}

internal external interface NobleUtils : JsAny {
    fun isValidSecretKey(secretKey: Uint8Array): Boolean
}

internal external interface NobleEcdsa : JsAny {
    val utils: NobleUtils
    fun getSharedSecret(secretKeyA: Uint8Array, publicKeyB: Uint8Array, isCompressed: Boolean): Uint8Array
    fun recoverPublicKey(signature: Uint8Array, message: Uint8Array, opts: JsAny): Uint8Array
}

internal external val schnorr: NobleSchnorr
internal external val secp256k1: NobleEcdsa
