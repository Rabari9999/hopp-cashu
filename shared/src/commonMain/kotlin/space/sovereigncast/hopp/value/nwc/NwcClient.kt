package space.sovereigncast.hopp.value.nwc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
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
import kotlinx.serialization.json.JsonNull
import space.sovereigncast.hopp.crypto.Sha256
import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.crypto.toHex
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.nostr.Nip44
import space.sovereigncast.hopp.nostr.RelayConnector
import space.sovereigncast.hopp.nostr.RelaySession
import space.sovereigncast.hopp.value.Bolt11
import space.sovereigncast.hopp.value.TlvRecord
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import space.sovereigncast.hopp.value.WalletClient

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
) : WalletClient {
    private val walletKey = connection.walletPubkey.hexToBytes()
    private val conversationKey by lazy { Nip44.conversationKey(connection.clientKeys.secretKey, walletKey) }
    private val json = Json { ignoreUnknownKeys = true }

    /** The wallet's capabilities, from its replaceable info event (kind 13194). */
    override suspend fun info(): WalletInfo = withSession { session ->
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

    override suspend fun payInvoice(invoice: String, amountMsat: Long?): PaymentResult {
        val paymentHash = runCatching { Bolt11.parse(invoice).paymentHash }.getOrNull()
        val result = request("pay_invoice", buildJsonObject {
            put("invoice", invoice)
            amountMsat?.let { put("amount", it) }
        })
        return proof(result, paymentHash)
    }

    override suspend fun payKeysend(amountMsat: Long, pubkey: String, tlvRecords: List<TlvRecord>): PaymentResult {
        val result = request("pay_keysend", buildJsonObject {
            put("amount", amountMsat)
            put("pubkey", pubkey)
            putJsonArray("tlv_records") {
                tlvRecords.forEach { r -> add(buildJsonObject { put("type", r.type); put("value", r.valueHex) }) }
            }
        })
        return proof(result, null)
    }

    /**
     * What a payment's answer has to show before Hopp counts it as paid
     * (security audit 2026-10-18, M-02): a 32-byte preimage — for an invoice,
     * the one its payment hash commits to — and fees that are a number of msat,
     * not below zero. The wallet's signature says who answered, not that the
     * answer is complete; an answer short of this is an unknown outcome, never
     * paid and never failed.
     */
    private fun proof(result: JsonObject, paymentHash: String?): PaymentResult {
        val preimage = (result["preimage"] as? JsonPrimitive)?.contentOrNull?.lowercase()
            ?.takeIf { HEX_32.matches(it) } ?: throw unknown("answer without a preimage")
        if (paymentHash != null && Sha256.digest(preimage.hexToBytes()).toHex() != paymentHash.lowercase()) {
            throw unknown("the preimage is not the invoice's")
        }
        val fees = when (val f = result["fees_paid"]) {
            null, JsonNull -> null
            else -> (f as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 } ?: throw unknown("fees_paid is not a number of msat")
        }
        return PaymentResult(preimage, fees)
    }

    private fun unknown(why: String) = NwcException(NwcException.OUTCOME_UNKNOWN, why)

    override suspend fun balanceMsat(): Long =
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
            // A dropped connection ends or fails the flow: null, never a failed
            // child — that would cancel this scope with its own exception, past
            // the OUTCOME_UNKNOWN below (code review 2026-10-17, H1).
            val response = async(start = CoroutineStart.UNDISPATCHED) {
                session.incoming.catch { }.mapNotNull { parseRelayMessage(it) }.firstOrNull { m ->
                    (m.type == "EVENT" && m.subscription == sub && m.event?.tag("e") == request.id) ||
                        (m.type == "OK" && m.subscription == request.id && m.ok == false)
                }
            }
            session.send(buildJsonArray { add(JsonPrimitive("REQ")); add(JsonPrimitive(sub)); add(filter) }.toString())
            // Cancelled while the EVENT goes out, it may be out: unknown, as
            // after it (security audit 2026-10-18) — not a cancellation, which
            // tells the caller nothing was sent and the share may be paid again.
            // The same for a send that fails with any other exception (security
            // audit 2026-10-20, H-01): the socket may close after the frame has
            // left; the relay — or the wallet — may well have it.
            try {
                session.send(buildJsonArray { add(JsonPrimitive("EVENT")); add(json.encodeToJsonElement(Event.serializer(), request)) }.toString())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw unknown("cancelled while sending")
            } catch (e: Exception) {
                throw unknown("the send failed (${e.message ?: e::class.simpleName}); the request may be out")
            }
            // From here on the wallet may have the request: only its own answer —
            // a result, or an error it reports — says what happened. A timeout, a
            // dropped connection, the relay's refusal or an answer that cannot be
            // read do not mean "not paid".
            val msg = try {
                withTimeout(timeout) { response.await() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw NwcException(NwcException.OUTCOME_UNKNOWN, "no answer from the wallet within $timeout")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The EVENT is already on the relay. A parent cancel here is the
                // same as a dropped connection: the wallet may have paid (M1).
                // Reported as that, not as the cancellation: a caller tells
                // "cancelled before sending" (CancellationException, nothing
                // sent) from this, and settles or keeps the share accordingly.
                // The scope finishes with this exception, not the cancellation.
                throw NwcException(NwcException.OUTCOME_UNKNOWN, "cancelled after sending")
            } ?: throw NwcException(NwcException.OUTCOME_UNKNOWN, "connection lost after sending")
            // A relay's "refused" is not the wallet's word and is not signed: a
            // relay may pass the request on and say it refused it. Not a reason
            // to pay again (security audit 2026-10-18, H-01).
            if (msg.type == "OK") throw unknown("relay refused the request (${msg.message ?: "no reason"}); it may have passed it on")
            val body = runCatching {
                val event = msg.event!!
                check(event.isValid() && event.pubkey == connection.walletPubkey) { "invalid response event" }
                json.parseToJsonElement(Nip44.decrypt(event.content, conversationKey)).jsonObject
            }.getOrElse { throw NwcException(NwcException.OUTCOME_UNKNOWN, "unreadable answer: ${it.message}") }
            // An answer to another method proves nothing about this one (M-02).
            // Every field read as "if it has this shape": an authentic answer
            // of the wrong shape — a scalar result, say — is unknown, never an
            // exception that counts as "not paid" (security audit 2026-10-20, M-01).
            val type = (body["result_type"] as? JsonPrimitive)?.contentOrNull
            if (type != method) throw unknown("answer for ${type ?: "no method"}, not $method")
            (body["error"] as? JsonObject)?.let { err ->
                throw NwcException((err["code"] as? JsonPrimitive)?.contentOrNull ?: "ERROR", (err["message"] as? JsonPrimitive)?.contentOrNull ?: "")
            }
            body["result"] as? JsonObject ?: throw unknown("answer without a result object")
        }
    }

    private suspend fun <T> withSession(block: suspend CoroutineScope.(RelaySession) -> T): T {
        var lastError: Exception? = null
        for (relay in connection.relays) {
            val session = try {
                connector.connect(relay)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // cancelled, not unreachable: the next relay would not help
            } catch (e: Exception) { lastError = e; continue }
            try {
                return coroutineScope { block(session) }
            } finally {
                runCatching { session.close() }
            }
        }
        throw NwcException("RELAY_UNREACHABLE", lastError?.message ?: "no relay reachable")
    }

    private companion object {
        val HEX_32 = Regex("[0-9a-f]{64}")
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
