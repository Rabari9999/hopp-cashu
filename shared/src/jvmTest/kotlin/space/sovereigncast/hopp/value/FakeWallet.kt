package space.sovereigncast.hopp.value

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.nostr.Nip44

/** A wallet service on the fake relay: answers NIP-47 requests the way a real
 *  one does, with real NIP-44 crypto, and records what it was asked to pay. */
class FakeWallet(
    private val relay: FakeRelay,
    val keys: KeyPair = KeyPair.generate(),
    private val methods: List<String> = listOf("pay_invoice", "pay_keysend", "get_balance", "get_info"),
    private val encryption: String = "nip44_v2 nip04",
) {
    val requests = mutableListOf<Pair<String, JsonObject>>()
    var failWith: Pair<String, String>? = null
    /** Fail only this method (e.g. pay_keysend) — the others answer. */
    var failMethod: String? = null
    /** Pays this method but never answers — the answer lost on the relay. */
    var silentMethod: String? = null
    /** What get_info names as the wallet's node. */
    var nodePubkey: String? = null
    /** Changes an answer before it is signed — a wallet that answers wrongly but authentically. */
    var tamper: ((JsonObject) -> JsonObject)? = null
    private val json = Json

    init {
        relay.listeners += { e -> if (e.kind == 23194 && e.tag("p") == keys.publicKeyHex) handle(e) }
    }

    fun publishInfo() = relay.publish(
        Event.sign(keys, 13194, methods.joinToString(" "), listOf(listOf("encryption", encryption)), createdAt = 1),
    )

    /** Handles one request event the client published. */
    fun handle(request: Event) {
        val ck = Nip44.conversationKey(keys.secretKey, request.pubkey.hexToBytes())
        val body = json.parseToJsonElement(Nip44.decrypt(request.content, ck)).jsonObject
        val method = body["method"]!!.jsonPrimitive.content
        requests += method to body["params"]!!.jsonObject
        if (method == silentMethod) return
        val response = buildJsonObject {
            put("result_type", method)
            val fail = failWith ?: ("PAYMENT_FAILED" to "no route").takeIf { failMethod == method }
            if (fail != null) put("error", buildJsonObject { put("code", fail.first); put("message", fail.second) })
            else put("result", when (method) {
                "get_balance" -> buildJsonObject { put("balance", 21_000_000) }
                "get_info" -> buildJsonObject { put("alias", "Fake Hub"); nodePubkey?.let { put("pubkey", it) } }
                else -> buildJsonObject { put("preimage", "00".repeat(32)); put("fees_paid", 1000) }
            })
        }.let { tamper?.invoke(it) ?: it }.toString()
        relay.publish(Event.sign(keys, 23195, Nip44.encrypt(response, ck),
            listOf(listOf("p", request.pubkey), listOf("e", request.id)), createdAt = request.created_at))
    }
}
