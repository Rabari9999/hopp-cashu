package space.sovereigncast.hopp.crypto

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** Hopp's AES-256-CBC (private zaps) against FIPS-197 and the JVM's. */
class AesTest {
    @Test
    fun fipsVector() {
        // FIPS-197, C.3: one block of AES-256; CBC with a zero IV is the plain cipher on the first block.
        val key = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f".hexToBytes()
        val block = "00112233445566778899aabbccddeeff".hexToBytes()
        assertContentEquals("8ea2b7ca516745bfeafc49904b496089".hexToBytes(), Aes.encryptCbc(key, ByteArray(16), block).copyOf(16))
    }

    @Test
    fun matchesTheJvmForAnyLength() {
        val random = Random(42)
        for (length in listOf(0, 1, 15, 16, 17, 31, 32, 100, 1000)) {
            val key = random.nextBytes(32); val iv = random.nextBytes(16); val text = random.nextBytes(length)
            val jvm = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }.doFinal(text)
            assertContentEquals(jvm, Aes.encryptCbc(key, iv, text), "length $length")
        }
    }
}
