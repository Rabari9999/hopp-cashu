package space.sovereigncast.hopp.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import space.sovereigncast.hopp.TestVectors
import space.sovereigncast.hopp.crypto.Sha256
import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.crypto.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The official NIP-44 v2 vectors (github.com/paulmillr/nip44) — in common
 *  code, so they also check `@noble/curves` on the web (ADR 0022). */
class Nip44VectorsTest {
    private val v2: JsonObject = Json.parseToJsonElement(TestVectors.NIP44).jsonObject["v2"]!!.jsonObject
    private val valid = v2["valid"]!!.jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test
    fun conversationKeys() {
        val cases = valid["get_conversation_key"]!!.jsonArray
        for (c in cases.map { it.jsonObject }) {
            val key = Nip44.conversationKey(c.s("sec1").hexToBytes(), c.s("pub2").hexToBytes())
            assertEquals(c.s("conversation_key"), key.toHex(), c.toString())
        }
        println("conversation keys: ${cases.size}")
    }

    @Test
    fun messageKeys() {
        val o = valid["get_message_keys"]!!.jsonObject
        val ck = o.s("conversation_key").hexToBytes()
        for (k in o["keys"]!!.jsonArray.map { it.jsonObject }) {
            val m = Nip44.messageKeys(ck, k.s("nonce").hexToBytes())
            assertEquals(k.s("chacha_key"), m.chachaKey.toHex())
            assertEquals(k.s("chacha_nonce"), m.chachaNonce.toHex())
            assertEquals(k.s("hmac_key"), m.hmacKey.toHex())
        }
    }

    @Test
    fun paddedLengths() {
        for (pair in valid["calc_padded_len"]!!.jsonArray) {
            val (len, padded) = pair.jsonArray.map { it.jsonPrimitive.int }
            assertEquals(padded, Nip44.paddedLength(len), "length $len")
        }
    }

    @Test
    fun encryptAndDecrypt() {
        val cases = valid["encrypt_decrypt"]!!.jsonArray.map { it.jsonObject }
        for (c in cases) {
            val sec1 = c.s("sec1").hexToBytes()
            val pub2 = KeyPair(c.s("sec2").hexToBytes()).publicKey
            val ck = Nip44.conversationKey(sec1, pub2)
            assertEquals(c.s("conversation_key"), ck.toHex())
            assertEquals(c.s("payload"), Nip44.encrypt(c.s("plaintext"), ck, c.s("nonce").hexToBytes()))
            assertEquals(c.s("plaintext"), Nip44.decrypt(c.s("payload"), ck))
        }
        println("encrypt/decrypt: ${cases.size}")
    }

    @Test
    fun longMessages() {
        for (c in valid["encrypt_decrypt_long_msg"]!!.jsonArray.map { it.jsonObject }) {
            val plaintext = c.s("pattern").repeat(c["repeat"]!!.jsonPrimitive.int)
            assertEquals(c.s("plaintext_sha256"), Sha256.digest(plaintext.encodeToByteArray()).toHex())
            val ck = c.s("conversation_key").hexToBytes()
            val payload = Nip44.encrypt(plaintext, ck, c.s("nonce").hexToBytes())
            assertEquals(c.s("payload_sha256"), Sha256.digest(payload.encodeToByteArray()).toHex())
            assertEquals(plaintext, Nip44.decrypt(payload, ck))
        }
    }

    @Test
    fun invalidKeysAndLengthsAreRejected() {
        val invalid = v2["invalid"]!!.jsonObject
        for (c in invalid["get_conversation_key"]!!.jsonArray.map { it.jsonObject }) {
            assertFailsWith<Exception>(c.s("note")) { Nip44.conversationKey(c.s("sec1").hexToBytes(), c.s("pub2").hexToBytes()) }
        }
        val ck = ByteArray(32) { 1 }
        for (length in invalid["encrypt_msg_lengths"]!!.jsonArray.map { it.jsonPrimitive.int }) {
            assertFailsWith<Nip44Exception>("length $length") { Nip44.encrypt("a".repeat(length), ck) }
        }
    }

    @Test
    fun invalidPayloadsAreRejected() {
        val invalid = v2["invalid"]!!.jsonObject["decrypt"]!!.jsonArray.map { it.jsonObject }
        for (c in invalid) {
            assertFailsWith<Exception>(c.s("note")) { Nip44.decrypt(c.s("payload"), c.s("conversation_key").hexToBytes()) }
        }
        assertTrue(invalid.isNotEmpty())
    }
}
