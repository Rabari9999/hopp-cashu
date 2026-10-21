package space.sovereigncast.hopp.nostr

import space.sovereigncast.hopp.crypto.AeadException
import space.sovereigncast.hopp.crypto.Scrypt
import space.sovereigncast.hopp.crypto.XChaCha20Poly1305
import space.sovereigncast.hopp.crypto.secureRandomBytes

/** Unicode NFKC — NIP-49 normalises the password with it, so the same
 *  password typed on another keyboard still opens the key. */
expect fun normalizeNfkc(text: String): String

class Nip49Exception(message: String) : Exception(message)

/**
 * NIP-49: a private key encrypted with a password (`ncryptsec1…`), the form
 * Hopp exports a key it made (ADR 0021, 9). scrypt(log_n, r 8, p 1) →
 * XChaCha20-Poly1305 with the key-security byte as associated data.
 */
object Nip49 {
    const val LOG_N = 16
    /** The most an imported key may ask for: scrypt takes 128·8·2^log_n bytes —
     *  256 MiB here, 4 GiB at the format's 22 — and a phone has no such heap
     *  (security audit 2026-10-20, L-01). Exports use [LOG_N], as other clients do. */
    const val MAX_LOG_N = 18

    /** Hopp knows nothing of how the key was handled before it made it, but
     *  it never left the device unencrypted: 0x01. */
    const val KEY_NOT_KNOWN_INSECURE: Byte = 0x01

    fun encrypt(secretKey: ByteArray, password: String, logN: Int = LOG_N, keySecurity: Byte = KEY_NOT_KNOWN_INSECURE): String {
        require(secretKey.size == 32)
        val salt = secureRandomBytes(16)
        val nonce = secureRandomBytes(24)
        val key = symmetricKey(password, salt, logN)
        val sealed = try { XChaCha20Poly1305.encrypt(key, nonce, secretKey, byteArrayOf(keySecurity)) } finally { key.fill(0) }
        return Bech32.encode("ncryptsec", byteArrayOf(0x02, logN.toByte()) + salt + nonce + byteArrayOf(keySecurity) + sealed)
    }

    fun decrypt(ncryptsec: String, password: String): ByteArray {
        val (hrp, data) = try { Bech32.decode(ncryptsec) } catch (e: Exception) { throw Nip49Exception("not an ncryptsec") }
        if (hrp != "ncryptsec" || data.size != 91 || data[0] != 0x02.toByte()) throw Nip49Exception("not an ncryptsec")
        val logN = data[1].toInt() and 0xff
        if (logN !in 1..MAX_LOG_N) throw Nip49Exception("unsupported log_n $logN")
        val salt = data.copyOfRange(2, 18)
        val nonce = data.copyOfRange(18, 42)
        val ad = data.copyOfRange(42, 43)
        val key = symmetricKey(password, salt, logN)
        return try { XChaCha20Poly1305.decrypt(key, nonce, data.copyOfRange(43, 91), ad) }
        catch (e: AeadException) { throw Nip49Exception("wrong password") }
        finally { key.fill(0) }
    }

    private fun symmetricKey(password: String, salt: ByteArray, logN: Int): ByteArray =
        Scrypt.derive(normalizeNfkc(password).encodeToByteArray(), salt, 1 shl logN, 8, 1, 32)
}
