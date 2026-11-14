package space.sovereigncast.hopp.value.cashu

import io.ktor.client.HttpClient
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import space.sovereigncast.hopp.crypto.Sha256
import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.crypto.toHex
import space.sovereigncast.hopp.net.LimitedResponse
import space.sovereigncast.hopp.net.Limits
import space.sovereigncast.hopp.net.getLimited
import space.sovereigncast.hopp.net.limited

/** The mint answered, and said no: nothing happened (unlike a dropped connection, whose outcome is unknown). */
class CashuMintException(val code: Int?, val detail: String, val status: Int) :
    Exception("mint refused (${code ?: "HTTP $status"}): $detail")

// NUT-06
@Serializable
data class MintInfo(val name: String? = null, val version: String? = null, val nuts: JsonObject = JsonObject(emptyMap()))

// NUT-02
@Serializable
data class KeysetInfo(
    val id: String,
    val unit: String,
    val active: Boolean = true,
    @SerialName("input_fee_ppk") val inputFeePpk: Long = 0,
    @SerialName("final_expiry") val finalExpiry: Long? = null,
)

// NUT-01; amounts stay strings: a keyset may go up to 2^63, past a Long.
@Serializable
data class MintKeyset(val id: String, val unit: String = "", val keys: Map<String, String>)

// NUT-00
@Serializable
data class BlindedMessage(val amount: Long, val id: String, @SerialName("B_") val b: String)

@Serializable
data class BlindSignatureDleq(val e: String, val s: String)

@Serializable
data class BlindSignature(val amount: Long, val id: String, @SerialName("C_") val c: String, val dleq: BlindSignatureDleq? = null)

// NUT-04. Mints before the `state` field said `paid`.
@Serializable
data class MintQuote(
    val quote: String,
    val request: String,
    val state: String? = null,
    val paid: Boolean? = null,
    val expiry: Long? = null,
) {
    /** UNPAID, PAID or ISSUED. */
    val status: String get() = state ?: if (paid == true) "PAID" else "UNPAID"
}

// NUT-05
@Serializable
data class MeltQuote(
    val quote: String,
    val amount: Long,
    @SerialName("fee_reserve") val feeReserve: Long,
    val state: String? = null,
    val paid: Boolean? = null,
    val expiry: Long? = null,
    @SerialName("payment_preimage") val paymentPreimage: String? = null,
    val change: List<BlindSignature>? = null,
) {
    /** UNPAID, PENDING or PAID. */
    val status: String get() = state ?: if (paid == true) "PAID" else "UNPAID"
}

// NUT-07
@Serializable
data class ProofState(@SerialName("Y") val y: String, val state: String)

/**
 * One Cashu mint's HTTP API (NUT-01 to NUT-07), over Hopp's client with its
 * read limits. Only https, or http to an onion service: proofs are bearer
 * money and a plain-http mint would show them to everyone on the way.
 *
 * A refusal throws [CashuMintException]; a network failure propagates as is,
 * and for a melt it means the outcome is unknown, which the wallet resolves later.
 */
class MintClient(private val http: HttpClient, mintUrl: String) {
    val url: String = normalize(mintUrl)

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun info(): MintInfo = get("/v1/info", MintInfo.serializer())

    suspend fun keysets(): List<KeysetInfo> = get("/v1/keysets", KeysetList.serializer()).keysets

    /** The keys of [keyset], refused unless they hash to its id (NUT-02: a mint must not hand one wallet its own keys to tag it). */
    suspend fun keys(keyset: KeysetInfo): Map<String, String> {
        val answer = get("/v1/keys/${keyset.id.encodeURLPathPart()}", KeysetKeys.serializer()).keysets
        val keys = answer.firstOrNull { it.id == keyset.id }?.keys ?: throw CashuMintException(null, "keyset ${keyset.id} missing", 200)
        if (!KeysetId.matches(keyset, keys)) throw CashuMintException(null, "keys do not match keyset id ${keyset.id}", 200)
        return keys
    }

    suspend fun mintQuote(amount: Long, unit: String = "sat"): MintQuote =
        post("/v1/mint/quote/bolt11", MintQuoteRequest(amount, unit), MintQuoteRequest.serializer(), MintQuote.serializer())

    suspend fun mintQuoteState(quote: String): MintQuote = get("/v1/mint/quote/bolt11/${quote.encodeURLPathPart()}", MintQuote.serializer())

    suspend fun mint(quote: String, outputs: List<BlindedMessage>): List<BlindSignature> =
        post("/v1/mint/bolt11", MintRequest(quote, outputs), MintRequest.serializer(), Signatures.serializer()).signatures

    suspend fun swap(inputs: List<Proof>, outputs: List<BlindedMessage>): List<BlindSignature> =
        post("/v1/swap", SwapRequest(inputs.map { it.forMint() }, outputs), SwapRequest.serializer(), Signatures.serializer()).signatures

    suspend fun meltQuote(invoice: String, unit: String = "sat"): MeltQuote =
        post("/v1/melt/quote/bolt11", MeltQuoteRequest(invoice, unit), MeltQuoteRequest.serializer(), MeltQuote.serializer())

    suspend fun meltQuoteState(quote: String): MeltQuote = get("/v1/melt/quote/bolt11/${quote.encodeURLPathPart()}", MeltQuote.serializer())

    /** [outputs]: blank outputs for the change of an overpaid fee reserve (NUT-08). */
    suspend fun melt(quote: String, inputs: List<Proof>, outputs: List<BlindedMessage>): MeltQuote =
        post("/v1/melt/bolt11", MeltRequest(quote, inputs.map { it.forMint() }, outputs), MeltRequest.serializer(), MeltQuote.serializer())

    /** [ys]: hash_to_curve of each proof's secret, compressed hex. */
    suspend fun checkState(ys: List<String>): List<ProofState> =
        post("/v1/checkstate", CheckStateRequest(ys), CheckStateRequest.serializer(), States.serializer()).states

    // A proof's DLEQ carries r, its blinding factor: sent to the mint, it would link the proof to its signing.
    private fun Proof.forMint() = copy(dleq = null)

    private suspend fun <T> get(path: String, serializer: KSerializer<T>): T = read(http.getLimited(url + path, Limits.API), serializer)

    private suspend fun <B, T> post(path: String, body: B, bodySerializer: KSerializer<B>, serializer: KSerializer<T>): T {
        val text = json.encodeToString(bodySerializer, body)
        return read(http.limited(Limits.API) { url(this@MintClient.url + path); method = HttpMethod.Post; contentType(ContentType.Application.Json); setBody(text) }, serializer)
    }

    private fun <T> read(response: LimitedResponse, serializer: KSerializer<T>): T {
        val text = response.text()
        if (!response.status.isSuccess()) {
            val error = runCatching { json.decodeFromString(ErrorBody.serializer(), text) }.getOrNull()
            throw CashuMintException(error?.code, error?.detail ?: text.take(200), response.status.value)
        }
        return json.decodeFromString(serializer, text)
    }

    @Serializable private data class KeysetList(val keysets: List<KeysetInfo>)
    @Serializable private data class KeysetKeys(val keysets: List<MintKeyset>)
    @Serializable private data class Signatures(val signatures: List<BlindSignature>)
    @Serializable private data class States(val states: List<ProofState>)
    @Serializable private data class ErrorBody(val detail: String? = null, val code: Int? = null)
    @Serializable private data class MintQuoteRequest(val amount: Long, val unit: String)
    @Serializable private data class MintRequest(val quote: String, val outputs: List<BlindedMessage>)
    @Serializable private data class SwapRequest(val inputs: List<Proof>, val outputs: List<BlindedMessage>)
    @Serializable private data class MeltQuoteRequest(val request: String, val unit: String)
    @Serializable private data class MeltRequest(val quote: String, val inputs: List<Proof>, val outputs: List<BlindedMessage>)
    @Serializable private data class CheckStateRequest(@SerialName("Ys") val ys: List<String>)

    companion object {
        /** The mint's base URL without a trailing slash; IllegalArgumentException unless https or an onion service. */
        fun normalize(mintUrl: String): String {
            val trimmed = mintUrl.trim().trimEnd('/')
            val parsed = runCatching { Url(trimmed) }.getOrNull() ?: throw IllegalArgumentException("not a URL: $mintUrl")
            val ok = when (parsed.protocol.name) {
                "https" -> true
                "http" -> parsed.host.endsWith(".onion", ignoreCase = true)
                else -> false
            }
            require(ok && parsed.host.isNotEmpty() && "://" in trimmed) { "a mint must be https: $mintUrl" }
            return trimmed
        }
    }
}

/** NUT-02 keyset ids: v1 (`00`, 8 bytes) and v2 (`01`, 33 bytes), derived from the keys. */
internal object KeysetId {
    private fun isAmount(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' } && (s == "0" || s[0] != '0')

    // numerical order on decimal strings of any size
    private fun sorted(keys: Map<String, String>): List<Map.Entry<String, String>> {
        require(keys.isNotEmpty() && keys.keys.all(::isAmount)) { "keyset amounts" }
        return keys.entries.sortedWith(compareBy({ it.key.length }, { it.key }))
    }

    fun v1(keys: Map<String, String>): String =
        "00" + Sha256.digest(sorted(keys).fold(ByteArray(0)) { acc, e -> acc + e.value.hexToBytes() }).toHex().take(14)

    fun v2(keys: Map<String, String>, unit: String, inputFeePpk: Long, finalExpiry: Long?): String {
        val preimage = buildString {
            append(sorted(keys).joinToString(",") { "${it.key}:${it.value.lowercase()}" })
            append("|unit:").append(unit.lowercase())
            if (inputFeePpk != 0L) append("|input_fee_ppk:").append(inputFeePpk)
            if (finalExpiry != null && finalExpiry != 0L) append("|final_expiry:").append(finalExpiry)
        }
        return "01" + Sha256.digest(preimage.encodeToByteArray()).toHex()
    }

    fun matches(keyset: KeysetInfo, keys: Map<String, String>): Boolean = try {
        when {
            keyset.id.startsWith("00") && keyset.id.length == 16 -> v1(keys) == keyset.id.lowercase()
            keyset.id.startsWith("01") && keyset.id.length == 66 -> v2(keys, keyset.unit, keyset.inputFeePpk, keyset.finalExpiry) == keyset.id.lowercase()
            else -> false
        }
    } catch (_: IllegalArgumentException) {
        false
    }
}
