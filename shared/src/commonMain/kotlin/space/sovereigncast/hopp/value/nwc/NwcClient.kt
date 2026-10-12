package space.sovereigncast.hopp.value.nwc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.nostr.Nip44
import space.sovereigncast.hopp.nostr.RelayConnector
import space.sovereigncast.hopp.nostr.RelaySession
import space.sovereigncast.hopp.value.TlvRecord
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class NwcException(val code: String, message: String) : Exception("$code: $message") {
    /** The request reached the wallet, but no answer said what became of it —
     *  it may have paid. Never a reason to pay again (security report 2026-10-10, H-04). */
    val outcomeUnknown: Boolean get() = code == OUTCOME_UNKNOWN

    companion object {
        const val OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN"
    }
}

data class WalletInfo(
    val methods: Set<String>,
    val encryptions: Set<String>,
    val alias: String? = null,
    /** The node's key (get_info). Not the wallet's identity: sub-wallets share it. */
    val ownPubkey: String? = null,
    /** The wallet's own Lightning address (lud16 in the connection string) —
     *  the one thing that names exactly this (sub-)wallet. */
    val ownAddress: String? = null,
) {
    val canKeysend get() = "pay_keysend" in methods
    val canPayInvoice get() = "pay_invoice" in methods
}

data class PaymentResult(val preimage: String?, val feesPaidMsat: Long?)

/**
 * Nostr Wallet Connect client (NIP-47), NIP-44 encrypted.
 *
 * One request = one round trip on one relay session: subscribe to the
 * response *first* (kind 23195, tagged with our request id), then publish the
 * request (kind 23194), then wait. Wallets that only speak NIP-04 are refused
 * with a clear error rather than silently downgraded.
 */
class NwcClient(
    private val connection: NwcConnection,
    private val connector: RelayConnector,
    private val now: () -> Long,
    private val timeout: Duration = 60.seconds,
) {
    private val walletKey = connection.walletPubkey.hexToBytes()
    private val conversationKey by lazy { Nip44.conversationKey(connection.clientKeys.secretKey, walletKey) }
    private val json = Json { ignoreUnknownKeys = true }

    /** The wallet's capabilities, from its replaceable info event (kind 13194). */
    suspend fun info(): WalletInfo = withSession { session ->
        val sub = "info"
        val filter = buildJsonObject {
            putJsonArray("kinds") { add(JsonPrimitive(13194)) }
            putJsonArray("authors") { add(JsonPrimitive(connection.walletPubkey)) }
            put("limit", 1)
        }
        coroutineScope {
            val answer = async(start = CoroutineStart.UNDISPATCHED) {
                session.incoming.mapNotNull { parseRelayMessage(it) }.first {
                    (it.type == "EVENT" && it.subscription == sub) || (it.type == "EOSE" && it.subscription == sub)
                }
            }
            session.send(buildJsonArray { add(JsonPrimitive("REQ")); add(JsonPrimitive(sub)); add(filter) }.toString())
            val msg = withTimeout(timeout) { answer.await() }
            val event = msg.event?.takeIf { it.isValid() && it.pubkey == connection.walletPubkey }
                ?: throw NwcException("NO_INFO", "wallet published no info event")
            WalletInfo(
                methods = event.content.split(' ', '\n').filter { it.isNotBlank() }.toSet(),
                encryptions = event.tag("encryption")?.split(' ')?.toSet() ?: setOf("nip04"),
            )
        }
    }

    /** NIP-47 get_info: the node's alias and key, if the wallet tells. */
    suspend fun nodeInfo(): Pair<String?, String?> {
        val result = request("get_info", buildJsonObject { })
        return result["alias"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } to
            result["pubkey"]?.jsonPrimitive?.contentOrNull?.lowercase()?.takeIf { it.length == 66 }
    }

    suspend fun payInvoice(invoice: String, amountMsat: Long? = null): PaymentResult {
        val result = request("pay_invoice", buildJsonObject {
            put("invoice", invoice)
            amountMsat?.let { put("amount", it) }
        })
        return PaymentResult(result["preimage"]?.jsonPrimitive?.contentOrNull, result["fees_paid"]?.jsonPrimitive?.longOrNull)
    }

    suspend fun payKeysend(amountMsat: Long, pubkey: String, tlvRecords: List<TlvRecord>): PaymentResult {
        val result = request("pay_keysend", buildJsonObject {
            put("amount", amountMsat)
            put("pubkey", pubkey)
            putJsonArray("tlv_records") {
                tlvRecords.forEach { r -> add(buildJsonObject { put("type", r.type); put("value", r.valueHex) }) }
            }
        })
        return PaymentResult(result["preimage"]?.jsonPrimitive?.contentOrNull, result["fees_paid"]?.jsonPrimitive?.longOrNull)
    }

    suspend fun balanceMsat(): Long =
        request("get_balance", JsonObject(emptyMap()))["balance"]?.jsonPrimitive?.longOrNull
            ?: throw NwcException("BAD_RESPONSE", "no balance in response")

    /** Sends one request and returns its `result` object, or throws the wallet's error. */
    suspend fun request(method: String, params: JsonObject): JsonObject = withSession { session ->
        val payload = buildJsonObject { put("method", method); put("params", params) }.toString()
        val request = Event.sign(
            connection.clientKeys, kind = 23194,
            content = Nip44.encrypt(payload, conversationKey),
            tags = listOf(listOf("p", connection.walletPubkey), listOf("encryption", "nip44_v2")),
            createdAt = now(),
        )
        val sub = "r" + request.id.take(16)
        val filter = buildJsonObject {
            putJsonArray("kinds") { add(JsonPrimitive(23195)) }
            putJsonArray("authors") { add(JsonPrimitive(connection.walletPubkey)) }
            putJsonArray("#e") { add(JsonPrimitive(request.id)) }
        }
        coroutineScope {
            val response = async(start = CoroutineStart.UNDISPATCHED) {
                session.incoming.mapNotNull { parseRelayMessage(it) }.first { m ->
                    (m.type == "EVENT" && m.subscription == sub && m.event?.tag("e") == request.id) ||
                        (m.type == "OK" && m.subscription == request.id && m.ok == false)
                }
            }
            session.send(buildJsonArray { add(JsonPrimitive("REQ")); add(JsonPrimitive(sub)); add(filter) }.toString())
            session.send(buildJsonArray { add(JsonPrimitive("EVENT")); add(json.encodeToJsonElement(Event.serializer(), request)) }.toString())
            // From here on the wallet may have the request: only its own answer —
            // a result, or an error it reports — or the relay's refusal says what
            // happened. A timeout, a dropped connection or an answer that cannot
            // be read do not mean "not paid".
            val msg = try {
                withTimeout(timeout) { response.await() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw NwcException(NwcException.OUTCOME_UNKNOWN, "no answer from the wallet within $timeout")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw NwcException(NwcException.OUTCOME_UNKNOWN, "connection lost after sending: ${e.message ?: e::class.simpleName}")
            }
            if (msg.type == "OK") throw NwcException("RELAY_REJECTED", msg.message ?: "relay refused the request")
            val body = runCatching {
                val event = msg.event!!
                check(event.isValid() && event.pubkey == connection.walletPubkey) { "invalid response event" }
                json.parseToJsonElement(Nip44.decrypt(event.content, conversationKey)).jsonObject
            }.getOrElse { throw NwcException(NwcException.OUTCOME_UNKNOWN, "unreadable answer: ${it.message}") }
            body["error"]?.takeIf { it is JsonObject }?.jsonObject?.let { err ->
                throw NwcException(err["code"]?.jsonPrimitive?.contentOrNull ?: "ERROR", err["message"]?.jsonPrimitive?.contentOrNull ?: "")
            }
            body["result"]?.jsonObject ?: throw NwcException(NwcException.OUTCOME_UNKNOWN, "answer without result")
        }
    }

    private suspend fun <T> withSession(block: suspend CoroutineScope.(RelaySession) -> T): T {
        var lastError: Exception? = null
        for (relay in connection.relays) {
            val session = try { connector.connect(relay) } catch (e: Exception) { lastError = e; continue }
            try {
                return coroutineScope { block(session) }
            } finally {
                runCatching { session.close() }
            }
        }
        throw NwcException("RELAY_UNREACHABLE", lastError?.message ?: "no relay reachable")
    }

    private data class RelayMessage(val type: String, val subscription: String?, val event: Event?, val ok: Boolean?, val message: String?)

    private fun parseRelayMessage(text: String): RelayMessage? = runCatching {
        val arr = json.parseToJsonElement(text).jsonArray
        when (val type = arr[0].jsonPrimitive.content) {
            "EVENT" -> RelayMessage(type, arr[1].jsonPrimitive.content, json.decodeFromJsonElement(Event.serializer(), arr[2]), null, null)
            "OK" -> RelayMessage(type, arr[1].jsonPrimitive.content, null, arr[2].jsonPrimitive.content == "true", arr.getOrNull(3)?.jsonPrimitive?.contentOrNull)
            "EOSE", "CLOSED", "NOTICE" -> RelayMessage(type, arr.getOrNull(1)?.jsonPrimitive?.contentOrNull, null, null, arr.getOrNull(2)?.jsonPrimitive?.contentOrNull)
            else -> null
        }
    }.getOrNull()

}
