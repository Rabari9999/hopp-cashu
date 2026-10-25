package space.sovereigncast.hopp.identity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.nostr.ExternalSigner
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.nostr.Nip44
import space.sovereigncast.hopp.nostr.SignerNeedsListener
import space.sovereigncast.hopp.nostr.SignerPermission
import space.sovereigncast.hopp.nostr.SignerRejected
import space.sovereigncast.hopp.nostr.SignerResult
import kotlin.test.assertEquals

/** Amber as Hopp sees it: a key elsewhere that signs on request. */
internal class FakeSignerApp(val keys: KeyPair = KeyPair.generate()) : ExternalSigner {
    var reject = false
    var signWith: KeyPair? = null
    val asked = mutableListOf<List<SignerPermission>>()
    /** Every request that reached the app: `nip44_encrypt`, `nip44_decrypt`, `sign_event:<kind>`. */
    val requests = mutableListOf<String>()
    override fun available() = true
    override suspend fun login(permissions: List<SignerPermission>): Pair<String, String> {
        asked += permissions
        if (reject) throw SignerRejected()
        return keys.publicKeyHex to "com.greenart7c3.nostrsigner"
    }
    /** What the listener allowed for good; anything else would open the app. */
    var allowed = true
    override suspend fun nip44(signerPackage: String, encrypt: Boolean, text: String, peer: String, currentUser: String, interactive: Boolean): String {
        assertEquals(keys.publicKeyHex, currentUser)
        if (!allowed && !interactive) throw SignerNeedsListener()
        if (reject) throw SignerRejected()
        requests += if (encrypt) "nip44_encrypt" else "nip44_decrypt"
        val key = Nip44.conversationKey(keys.secretKey, peer.hexToBytes())
        return if (encrypt) Nip44.encrypt(text, key) else Nip44.decrypt(text, key)
    }
    override suspend fun signEvent(signerPackage: String, eventJson: String, eventId: String, currentUser: String, interactive: Boolean): SignerResult {
        assertEquals("com.greenart7c3.nostrsigner", signerPackage)
        if (!allowed && !interactive) throw SignerNeedsListener()
        assertEquals(keys.publicKeyHex, currentUser)
        if (reject) throw SignerRejected()
        val o = Json.parseToJsonElement(eventJson).jsonObject
        val tags = o["tags"]!!.jsonArray.map { t -> t.jsonArray.map { it.jsonPrimitive.content } }
        requests += "sign_event:" + o["kind"]!!.jsonPrimitive.content
        val e = Event.sign(signWith ?: keys, o["kind"]!!.jsonPrimitive.content.toInt(), o["content"]!!.jsonPrimitive.content, tags, o["created_at"]!!.jsonPrimitive.content.toLong())
        return SignerResult(Json.encodeToString(Event.serializer(), e), null)
    }
}
