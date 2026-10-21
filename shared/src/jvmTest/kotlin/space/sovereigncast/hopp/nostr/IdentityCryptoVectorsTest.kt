package space.sovereigncast.hopp.nostr

import space.sovereigncast.hopp.crypto.AeadException
import space.sovereigncast.hopp.crypto.ChaCha20
import space.sovereigncast.hopp.crypto.Poly1305
import space.sovereigncast.hopp.crypto.Scrypt
import space.sovereigncast.hopp.crypto.XChaCha20Poly1305
import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.crypto.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The published vectors behind the Nostr identity (ADR 0021): a key's npub
 *  and its NIP-49 export must match every other Nostr client byte for byte. */
class IdentityCryptoVectorsTest {
    @Test fun poly1305Rfc8439() {
        val key = "85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b".hexToBytes()
        assertEquals("a8061dc1305136c6c22b8baf0c0127a9", Poly1305.mac(key, "Cryptographic Forum Research Group".encodeToByteArray()).toHex())
    }

    @Test fun hchacha20Draft() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = "000000090000004a0000000031415927".hexToBytes()
        assertEquals("82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc", ChaCha20.hchacha20(key, nonce).toHex())
    }

    @Test fun xchachaRoundTripsAndRefusesATamperedMessage() {
        val key = ByteArray(32) { (it * 7).toByte() }
        val nonce = ByteArray(24) { (it + 1).toByte() }
        val sealed = XChaCha20Poly1305.encrypt(key, nonce, "hello hopp".encodeToByteArray(), byteArrayOf(1))
        assertEquals("hello hopp", XChaCha20Poly1305.decrypt(key, nonce, sealed, byteArrayOf(1)).decodeToString())
        assertFailsWith<AeadException> { XChaCha20Poly1305.decrypt(key, nonce, sealed, byteArrayOf(0)) }
        sealed[0] = (sealed[0] + 1).toByte()
        assertFailsWith<AeadException> { XChaCha20Poly1305.decrypt(key, nonce, sealed, byteArrayOf(1)) }
    }

    @Test fun scryptRfc7914() {
        assertEquals(
            "77d6576238657b203b19ca42c18a0497f16b4844e3074ae8dfdffa3fede21442fcd0069ded0948f8326a753a0fc81f17e8d3e0fb2e0d3628cf35e20c38d18906",
            Scrypt.derive(ByteArray(0), ByteArray(0), 16, 1, 1, 64).toHex(),
        )
        assertEquals(
            "fdbabe1c9d3472007856e7190d01e9fe7c6ad7cbc8237830e77376634b3731622eaf30d92e22a3886ff109279d9830dac727afb94a83ee6d8360cbdfa2cc0640",
            Scrypt.derive("password".encodeToByteArray(), "NaCl".encodeToByteArray(), 1024, 8, 16, 64).toHex(),
        )
    }

    @Test fun nip19() {
        assertEquals("npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6", npub("3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"))
        assertEquals("3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d", npubToHex("npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"))
        assertEquals("nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5",
            Bech32.encode("nsec", "67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa".hexToBytes()))
        assertNull(npubToHex("npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w7"), "a bad checksum was accepted")
        assertNull(npubToHex("nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"), "an nsec passed for an npub")
    }

    @Test fun nip49Normalisation() {
        val input = byteArrayOf(0xE2.toByte(), 0x84.toByte(), 0xAB.toByte(), 0xE2.toByte(), 0x84.toByte(), 0xA6.toByte(), 0xE1.toByte(), 0xBA.toByte(), 0x9B.toByte(), 0xCC.toByte(), 0xA3.toByte())
        assertEquals("c385cea9e1b9a9", normalizeNfkc(input.decodeToString()).encodeToByteArray().toHex())
    }

    @Test fun nip49DecryptsTheSpecVector() {
        val key = Nip49.decrypt(
            "ncryptsec1qgg9947rlpvqu76pj5ecreduf9jxhselq2nae2kghhvd5g7dgjtcxfqtd67p9m0w57lspw8gsq6yphnm8623nsl8xn9j4jdzz84zm3frztj3z7s35vpzmqf6ksu8r89qk5z2zxfmu5gv8th8wclt0h4p",
            "nostr",
        )
        assertEquals("3501454135014541350145413501453fefb02227e449e57cf4d3a3ce05378683", key.toHex())
    }

    @Test fun nip49RoundTripsAndSaysWhenThePasswordIsWrong() {
        val secret = ByteArray(32) { (it + 3).toByte() }
        val exported = Nip49.encrypt(secret, "Pässwort", logN = 10)
        assert(exported.startsWith("ncryptsec1"))
        assertContentEquals(secret, Nip49.decrypt(exported, "Pässwort"))
        assertFailsWith<Nip49Exception> { Nip49.decrypt(exported, "falsch") }
        // Security audit 2026-10-20, L-01: a log_n past what a phone's heap takes
        // (22 would be 4 GiB) is refused before scrypt allocates anything.
        val tooStrong = Bech32.encode("ncryptsec", byteArrayOf(0x02, (Nip49.MAX_LOG_N + 1).toByte()) + ByteArray(16) + ByteArray(24) + byteArrayOf(0x01) + ByteArray(48))
        val refused = assertFailsWith<Nip49Exception> { Nip49.decrypt(tooStrong, "Pässwort") }
        assert("log_n" in refused.message!!)
    }
}
