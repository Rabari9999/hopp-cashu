package space.sovereigncast.hopp.nostr

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import space.sovereigncast.hopp.crypto.ChaCha20
import space.sovereigncast.hopp.crypto.constantTimeEquals
import space.sovereigncast.hopp.crypto.hkdfExpand
import space.sovereigncast.hopp.crypto.hkdfExtract
import space.sovereigncast.hopp.crypto.hmacSha256
import space.sovereigncast.hopp.crypto.secureRandomBytes

class Nip44Exception(message: String) : Exception(message)

/** NIP-44 v2 encryption — what NWC (NIP-47) uses between Hopp and the wallet.
 *  Checked against the official test vectors (paulmillr/nip44). */
@OptIn(ExperimentalEncodingApi::class)
object Nip44 {
    private val SALT = "nip44-v2".encodeToByteArray()

    fun conversationKey(secretKey: ByteArray, xOnlyPublicKey: ByteArray): ByteArray =
        hkdfExtract(SALT, sharedX(secretKey, xOnlyPublicKey))

    internal data class MessageKeys(val chachaKey: ByteArray, val chachaNonce: ByteArray, val hmacKey: ByteArray)

    internal fun messageKeys(conversationKey: ByteArray, nonce: ByteArray): MessageKeys {
        require(conversationKey.size == 32 && nonce.size == 32)
        val keys = hkdfExpand(conversationKey, nonce, 76)
        return MessageKeys(keys.copyOfRange(0, 32), keys.copyOfRange(32, 44), keys.copyOfRange(44, 76))
    }

    internal fun paddedLength(length: Int): Int {
        require(length >= 1)
        if (length <= 32) return 32
        val nextPower = 1 shl (32 - (length - 1).countLeadingZeroBits())
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((length - 1) / chunk + 1)
    }

    fun encrypt(plaintext: String, conversationKey: ByteArray, nonce: ByteArray = secureRandomBytes(32)): String {
        val bytes = plaintext.encodeToByteArray()
        if (bytes.isEmpty() || bytes.size > 65535) throw Nip44Exception("plaintext length ${bytes.size} out of range")
        val padded = ByteArray(2 + paddedLength(bytes.size))
        padded[0] = (bytes.size shr 8).toByte(); padded[1] = bytes.size.toByte()
        bytes.copyInto(padded, 2)
        val k = messageKeys(conversationKey, nonce)
        val ciphertext = ChaCha20.xor(k.chachaKey, k.chachaNonce, padded)
        val mac = hmacSha256(k.hmacKey, nonce + ciphertext)
        return Base64.encode(byteArrayOf(2) + nonce + ciphertext + mac)
    }

    fun decrypt(payload: String, conversationKey: ByteArray): String {
        if (payload.isEmpty() || payload[0] == '#') throw Nip44Exception("unknown encryption version")
        if (payload.length !in 132..87472) throw Nip44Exception("invalid payload length ${payload.length}")
        val data = try { Base64.decode(payload) } catch (e: IllegalArgumentException) { throw Nip44Exception("invalid base64") }
        if (data.size !in 99..65603 || data[0] != 2.toByte()) throw Nip44Exception("unknown version or bad length")
        val nonce = data.copyOfRange(1, 33)
        val ciphertext = data.copyOfRange(33, data.size - 32)
        val mac = data.copyOfRange(data.size - 32, data.size)
        val k = messageKeys(conversationKey, nonce)
        if (!constantTimeEquals(mac, hmacSha256(k.hmacKey, nonce + ciphertext))) throw Nip44Exception("invalid MAC")
        val padded = ChaCha20.xor(k.chachaKey, k.chachaNonce, ciphertext)
        val length = (padded[0].toInt() and 0xFF shl 8) or (padded[1].toInt() and 0xFF)
        if (length == 0 || padded.size != 2 + paddedLength(length)) throw Nip44Exception("invalid padding")
        return padded.decodeToString(2, 2 + length)
    }
}
