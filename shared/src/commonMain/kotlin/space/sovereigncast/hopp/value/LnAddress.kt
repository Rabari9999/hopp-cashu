package space.sovereigncast.hopp.value

import space.sovereigncast.hopp.net.getLimited
import space.sovereigncast.hopp.net.Limits
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import space.sovereigncast.hopp.crypto.Sha256
import space.sovereigncast.hopp.crypto.toHex

class LnAddressException(message: String) : Exception(message)

/** LUD-06 pay request behind a Lightning address; [nostrPubkey]: the key its zap
 *  receipts are signed with, when it takes zaps (NIP-57). */
data class PayRequest(val callback: String, val minMsat: Long, val maxMsat: Long, val metadata: String, val commentAllowed: Int, val nostrPubkey: String? = null)

/** `/.well-known/keysend/<user>` — the podcast namespace's keysend route for
 *  an lnaddress recipient, which lets a keysend-capable wallet send the full
 *  boostagram instead of a 200-character comment. */
data class KeysendInfo(val pubkey: String, val customKey: String?, val customValue: String?)

class LnAddressClient(private val http: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun parts(address: String): Pair<String, String> {
        val user = address.substringBefore('@').trim()
        val domain = address.substringAfter('@', "").trim().lowercase()
        if (user.isEmpty() || domain.isEmpty() || '/' in domain || ':' in domain) throw LnAddressException("not a Lightning address: $address")
        return user to domain
    }

    suspend fun payRequest(address: String): PayRequest {
        val (user, domain) = parts(address)
        val body = getJson("https://$domain/.well-known/lnurlp/$user")
        if (body["tag"]?.jsonPrimitive?.contentOrNull != "payRequest") throw LnAddressException("no payRequest at $address")
        return PayRequest(
            callback = body["callback"]!!.jsonPrimitive.content,
            minMsat = body["minSendable"]!!.jsonPrimitive.long,
            maxMsat = body["maxSendable"]!!.jsonPrimitive.long,
            metadata = body["metadata"]!!.jsonPrimitive.content,
            commentAllowed = body["commentAllowed"]?.jsonPrimitive?.intOrNull ?: 0,
            nostrPubkey = body["nostrPubkey"]?.jsonPrimitive?.contentOrNull?.lowercase()
                ?.takeIf { body["allowsNostr"]?.jsonPrimitive?.contentOrNull == "true" && it.length == 64 && it.all { c -> c in "0123456789abcdef" } },
        )
    }

    suspend fun keysendInfo(address: String): KeysendInfo? {
        val (user, domain) = parts(address)
        val body = runCatching { getJson("https://$domain/.well-known/keysend/$user") }.getOrNull() ?: return null
        if (body["tag"]?.jsonPrimitive?.contentOrNull != "keysend") return null
        val pubkey = body["pubkey"]?.jsonPrimitive?.contentOrNull ?: return null
        val custom = body["customData"]?.jsonArray?.firstOrNull()?.jsonObject
        return KeysendInfo(pubkey, custom?.get("customKey")?.jsonPrimitive?.contentOrNull, custom?.get("customValue")?.jsonPrimitive?.contentOrNull)
    }

    /**
     * Fetches an invoice for exactly [amountMsat] and checks it before anyone
     * pays it: the amount must match (ADR 0005) and the description hash must
     * commit to the pay request's metadata (LUD-06).
     */
    suspend fun invoice(request: PayRequest, amountMsat: Long, comment: String?): String =
        fetchInvoice(request, amountMsat, request.metadata) {
            if (!comment.isNullOrBlank() && request.commentAllowed > 0) parameter("comment", comment.take(request.commentAllowed))
        }

    /**
     * An invoice for a zap (NIP-57): [zapRequest] — the signed kind 9734, as
     * JSON — goes along, the address's server publishes the receipt once it is
     * paid, and the invoice's description hash must commit to the zap request.
     */
    suspend fun zapInvoice(request: PayRequest, amountMsat: Long, zapRequest: String): String {
        if (request.nostrPubkey == null) throw LnAddressException("no zaps at this address")
        return fetchInvoice(request, amountMsat, zapRequest) { parameter("nostr", zapRequest) }
    }

    private suspend fun fetchInvoice(request: PayRequest, amountMsat: Long, committed: String, params: io.ktor.client.request.HttpRequestBuilder.() -> Unit): String {
        if (amountMsat !in request.minMsat..request.maxMsat) throw LnAddressException("amount outside ${request.minMsat}..${request.maxMsat} msat")
        val response = http.getLimited(request.callback, Limits.LNURL) {
            parameter("amount", amountMsat)
            params()
        }
        val body = json.parseToJsonElement(response.text()).jsonObject
        if (body["status"]?.jsonPrimitive?.contentOrNull == "ERROR") throw LnAddressException(body["reason"]?.jsonPrimitive?.contentOrNull ?: "error")
        val pr = body["pr"]?.jsonPrimitive?.contentOrNull ?: throw LnAddressException("no invoice in response")
        val decoded = Bolt11.parse(pr)
        if (decoded.amountMsat != amountMsat) throw LnAddressException("invoice amount ${decoded.amountMsat} ≠ requested $amountMsat")
        val expectedHash = Sha256.digest(committed.encodeToByteArray()).toHex()
        if (decoded.descriptionHashHex != null && decoded.descriptionHashHex != expectedHash) {
            throw LnAddressException("invoice does not commit to the pay request")
        }
        return pr
    }

    private suspend fun getJson(url: String): JsonObject {
        val response = http.getLimited(url, Limits.LNURL)
        if (!response.status.isSuccess()) throw LnAddressException("HTTP ${response.status.value} from $url")
        return json.parseToJsonElement(response.text()).jsonObject
    }
}
