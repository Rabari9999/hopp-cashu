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

/** noble's projective point (v2); `multiply` takes a JS bigint in [1, n). */
internal external interface NoblePoint : JsAny {
    fun add(other: NoblePoint): NoblePoint
    fun negate(): NoblePoint
    fun multiply(scalar: JsAny): NoblePoint
    fun toBytes(isCompressed: Boolean): Uint8Array
}

internal external interface NoblePointClass : JsAny {
    val BASE: NoblePoint
    fun fromBytes(bytes: Uint8Array): NoblePoint
}

internal external interface NobleEcdsa : JsAny {
    val utils: NobleUtils
    val Point: NoblePointClass
    fun getSharedSecret(secretKeyA: Uint8Array, publicKeyB: Uint8Array, isCompressed: Boolean): Uint8Array
    fun recoverPublicKey(signature: Uint8Array, message: Uint8Array, opts: JsAny): Uint8Array
}

internal external val schnorr: NobleSchnorr
internal external val secp256k1: NobleEcdsa
