package space.sovereigncast.hopp.value.cashu

import space.sovereigncast.hopp.crypto.Secp256k1
import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.crypto.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The published Cashu vectors (cashubtc/nuts, tests/00-tests.md and tests/12-tests.md). */
class BdhkeVectorsTest {
    private fun h(s: String) = s.hexToBytes()

    @Test
    fun hashToCurve() {
        assertEquals(
            "024cce997d3b518f739663b757deaec95bcd9473c30a14ac2fd04023a739d1a725",
            Bdhke.hashToCurve(h("0000000000000000000000000000000000000000000000000000000000000000")).toHex(),
        )
        assertEquals(
            "022e7158e11c9506f1aa4248bf531298daa7febd6194f003edcd9b93ade6253acf",
            Bdhke.hashToCurve(h("0000000000000000000000000000000000000000000000000000000000000001")).toHex(),
        )
        // takes a few rounds of the counter
        assertEquals(
            "026cdbe15362df59cd1dd3c9c11de8aedac2106eca69236ecd9fbe117af897be4f",
            Bdhke.hashToCurve(h("0000000000000000000000000000000000000000000000000000000000000002")).toHex(),
        )
    }

    @Test
    fun blindedMessages() {
        assertEquals(
            "033b1a9737a40cc3fd9b6af4b723632b76a67a36782596304612a6c2bfb5197e6d",
            Bdhke.blind(
                h("d341ee4871f1f889041e63cf0d3823c713eea6aff01e80f1719f08f9e5be98f6"),
                h("99fce58439fc37412ab3468b73db0569322588f62fb3a49182d67e23d877824a"),
            ).toHex(),
        )
        assertEquals(
            "029bdf2d716ee366eddf599ba252786c1033f47e230248a4612a5670ab931f1763",
            Bdhke.blind(
                h("f1aaf16c2239746f369572c0784d9dd3d032d952c2d992175873fb58fae31a60"),
                h("f78476ea7cc9ade20f9e05e58a804cf19533f03ea805ece5fee88c8e2874ba50"),
            ).toHex(),
        )
    }

    @Test
    fun blindedSignatures() {
        val b = h("02a9acc1e48c25eeeb9289b5031cc57da9fe72f3fe2861d264bdc074209b107ba2")
        assertEquals(b.toHex(), Secp256k1.pointMul(b, h("0000000000000000000000000000000000000000000000000000000000000001")).toHex())
        assertEquals(
            "0398bc70ce8184d27ba89834d19f5199c84443c31131e48d3c1214db24247d005d",
            Secp256k1.pointMul(b, h("7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f")).toHex(),
        )
    }

    @Test
    fun unblindGivesKTimesY() {
        val secret = "407915bc212be61a77e3e6d2aeb4c727980bda51cd06a6afc29e2861768a7837".encodeToByteArray()
        val r = h("99fce58439fc37412ab3468b73db0569322588f62fb3a49182d67e23d877824a")
        val k = h("7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f")
        val blindSignature = Secp256k1.pointMul(Bdhke.blind(secret, r), k) // the mint's half
        val c = Bdhke.unblind(blindSignature, r, Secp256k1.publicKeyCompressed(k))
        assertEquals(Secp256k1.pointMul(Bdhke.hashToCurve(secret), k).toHex(), c.toHex())
    }

    @Test
    fun hashE() {
        val one = h("020000000000000000000000000000000000000000000000000000000000000001")
        assertEquals(
            "a4dc034b74338c28c6bc3ea49731f2a24440fc7c4affc08b31a93fc9fbe6401e",
            Bdhke.hashE(one, one, one, h("02a9acc1e48c25eeeb9289b5031cc57da9fe72f3fe2861d264bdc074209b107ba2")).toHex(),
        )
    }

    @Test
    fun dleqFromDeterministicNonce() {
        assertTrue(
            Bdhke.verifyDleq(
                e = h("2a16ffee280aff3c429045607f9b8e0bf8b35910c44c1b20b9dfaf01b263d7b3"),
                s = h("9df27731238334718d120d4f74611a7c668233f988e687ac3fb188f0a34a2dab"),
                mintKey = h("02c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"),
                blinded = h("02a9acc1e48c25eeeb9289b5031cc57da9fe72f3fe2861d264bdc074209b107ba2"),
                blindSignature = h("0244eccfc7a348274458bb38044c7f3c389b3c2086c7ec18b5812d2877ab937787"),
            ),
        )
    }

    private val g = h("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
    private val blindSigB = h("02a9acc1e48c25eeeb9289b5031cc57da9fe72f3fe2861d264bdc074209b107ba2")
    private val sigE = h("9818e061ee51d5c8edc3342369a554998ff7b4381c8652d724cdf46429be73d9")
    private val sigS = h("9818e061ee51d5c8edc3342369a554998ff7b4381c8652d724cdf46429be73da")

    @Test
    fun dleqOnBlindSignature() {
        assertTrue(Bdhke.verifyDleq(sigE, sigS, g, blindSigB, blindSigB))
        // a proof that does not fit: s off by one, then e and s swapped
        assertFalse(Bdhke.verifyDleq(sigE, sigE, g, blindSigB, blindSigB))
        assertFalse(Bdhke.verifyDleq(sigS, sigE, g, blindSigB, blindSigB))
    }

    @Test
    fun dleqOnProof() {
        val secret = "daf4dd00a2b68a0858a80450f52c8a7d2ccf87d375e43e216e0c571f089f63e9".encodeToByteArray()
        val c = h("024369d2d22a80ecf78f3937da9d5f30c1b9f74f0c32684d583cca0fa6a61cdcfc")
        val e = h("b31e58ac6527f34975ffab13e70a48b6d2b0d35abc4b03f0151f09ee1a9763d4")
        val s = h("8fbae004c59e754d71df67e392b6ae4e29293113ddc2ec86592a0431d16306d8")
        val r = h("a6d13fcd7a18442e6076f5e1e7c887ad5de40a019824bdfa9fe740d302e8d861")
        assertTrue(Bdhke.verifyProofDleq(secret, c, e, s, r, g))
        assertFalse(Bdhke.verifyProofDleq("tampered".encodeToByteArray(), c, e, s, r, g))
    }

    @Test
    fun malformedInputIsFalseNotACrash() {
        assertFalse(Bdhke.isPoint(h("05" + "00".repeat(32))))
        assertFalse(Bdhke.verifyDleq(sigE, ByteArray(32), g, blindSigB, blindSigB)) // s = 0 is no scalar
        assertFalse(Bdhke.verifyDleq(sigE, sigS, h("02" + "00".repeat(32)), blindSigB, blindSigB))
    }
}
