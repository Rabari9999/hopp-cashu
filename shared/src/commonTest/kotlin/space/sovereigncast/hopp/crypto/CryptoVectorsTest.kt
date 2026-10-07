package space.sovereigncast.hopp.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/** Published test vectors, so the hand-written primitives are pinned to the
 *  standards rather than to themselves. */
class CryptoVectorsTest {
    @Test
    fun sha256() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.digest(ByteArray(0)).toHex())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.digest("abc".encodeToByteArray()).toHex())
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).toHex(),
        )
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            Sha256.digest(ByteArray(1_000_000) { 'a'.code.toByte() }).toHex(),
        )
    }

    @Test
    fun hmacSha256Rfc4231() {
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            hmacSha256(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray()).toHex(),
        )
        // case 6: key longer than the block size
        assertEquals(
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            hmacSha256(ByteArray(131) { 0xaa.toByte() }, "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun hkdfRfc5869Case1() {
        val prk = hkdfExtract("000102030405060708090a0b0c".hexToBytes(), ByteArray(22) { 0x0b })
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", prk.toHex())
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            hkdfExpand(prk, "f0f1f2f3f4f5f6f7f8f9".hexToBytes(), 42).toHex(),
        )
    }

    @Test
    fun chacha20Rfc8439() {
        val key = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f".hexToBytes()
        val nonce = "000000000000004a00000000".hexToBytes()
        val plaintext = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."
        val ciphertext = ChaCha20.xor(key, nonce, plaintext.encodeToByteArray(), counter = 1)
        assertEquals(
            "6e2e359a2568f98041ba0728dd0d6981e97e7aec1d4360c20a27afccfd9fae0bf91b65c5524733ab8f593dabcd62b3571639d624e65152ab8f530c359f0861d807ca0dbf500d6a6156a38e088a22b65e52bc514d16ccf806818ce91ab77937365af90bbf74a35be6b40b8eedf2785e42874d",
            ciphertext.toHex(),
        )
    }
}
