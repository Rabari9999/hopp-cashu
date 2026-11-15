package space.sovereigncast.hopp.value.cashu

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.IOException

/**
 * A Cashu mint over a MockEngine, signing with [FakeMintKeys]: it checks
 * inputs as a mint does (signature, not spent, amounts balance after fees)
 * and can be told to pay melts, leave them pending, fail them, refuse them,
 * or do the work and drop the connection before answering.
 */
class FakeMint(val feePpk: Long = 0) {
    val keyset = FakeMintKeys()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    var meltAmount = 20L
    var feeReserve = 4L
    var lightningFee = 1L
    var meltOutcome = "PAID"
    var refuseMelt = false
    var dropAfterMelt = false
    var dropAfterSwap = false
    val preimage = "11".repeat(32)

    private val spentYs = mutableSetOf<String>()
    private val pendingYs = mutableSetOf<String>()
    private val signed = mutableMapOf<String, BlindSignature>()
    private val mintQuotes = mutableMapOf<String, Pair<Long, String>>()
    private val meltQuotes = mutableMapOf<String, MeltQuote>()
    private val pendingMelt = mutableMapOf<String, Pair<List<Proof>, List<BlindedMessage>>>()
    val calls = mutableListOf<String>()

    val http = HttpClient(MockEngine { req -> handle(req) })

    fun payInvoices() = mintQuotes.replaceAll { _, (amount, state) -> amount to if (state == "UNPAID") "PAID" else state }

    /** The pending melts' Lightning payments go through. */
    fun settlePendingMelts() {
        for ((id, work) in pendingMelt) {
            pendingYs -= work.first.map(Coins::y).toSet()
            meltQuotes[id] = pay(meltQuotes.getValue(id), work.first, work.second)
        }
        pendingMelt.clear()
    }

    private fun MockRequestHandleScope.ok(body: String) = respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
    private fun MockRequestHandleScope.refuse(detail: String, code: Int = 11000) =
        respond("""{"detail":"$detail","code":$code}""", HttpStatusCode.BadRequest, headersOf("Content-Type", "application/json"))

    private fun MockRequestHandleScope.handle(req: HttpRequestData): HttpResponseData {
        val path = req.url.encodedPath
        calls += "${req.method.value} $path"
        val body = if (req.method == HttpMethod.Post) json.parseToJsonElement((req.body as TextContent).text).jsonObject else JsonObject(emptyMap())
        fun <T> list(key: String, s: kotlinx.serialization.KSerializer<T>): List<T> =
            body[key]?.let { json.decodeFromJsonElement(ListSerializer(s), it) }.orEmpty()
        return when {
            path == "/v1/keysets" ->
                ok("""{"keysets":[{"id":"${keyset.id}","unit":"sat","active":true,"input_fee_ppk":$feePpk}]}""")
            path == "/v1/keys/${keyset.id}" ->
                ok(json.encodeToString(KeysAnswer.serializer(), KeysAnswer(listOf(MintKeyset(keyset.id, "sat", keyset.keys)))))
            path == "/v1/mint/quote/bolt11" && req.method == HttpMethod.Post -> {
                val id = "mint${mintQuotes.size}"
                mintQuotes[id] = body.getValue("amount").jsonPrimitive.long to "UNPAID"
                ok("""{"quote":"$id","request":"lnbcfake$id","state":"UNPAID","expiry":4102444800}""")
            }
            path.startsWith("/v1/mint/quote/bolt11/") -> {
                val id = path.substringAfterLast('/')
                ok("""{"quote":"$id","request":"lnbcfake$id","state":"${mintQuotes.getValue(id).second}"}""")
            }
            path == "/v1/mint/bolt11" -> {
                val id = body.getValue("quote").jsonPrimitive.content
                val (amount, state) = mintQuotes.getValue(id)
                val outputs = list("outputs", BlindedMessage.serializer())
                if (state != "PAID") return refuse("quote $state", 20001)
                if (outputs.sumOf { it.amount } != amount) return refuse("amounts")
                mintQuotes[id] = amount to "ISSUED"
                ok(signatures(outputs))
            }
            path == "/v1/swap" -> {
                val inputs = list("inputs", Proof.serializer())
                val outputs = list("outputs", BlindedMessage.serializer())
                check(inputs)?.let { return refuse(it, 11001) }
                if (outputs.sumOf { it.amount } != inputs.sumOf { it.amount } - fee(inputs)) return refuse("amounts")
                spentYs += inputs.map(Coins::y)
                val answer = signatures(outputs)
                if (dropAfterSwap) throw IOException("connection reset")
                ok(answer)
            }
            path == "/v1/melt/quote/bolt11" -> {
                val id = "melt${meltQuotes.size}"
                meltQuotes[id] = MeltQuote(id, meltAmount, feeReserve, "UNPAID")
                ok(json.encodeToString(MeltQuote.serializer(), meltQuotes.getValue(id)))
            }
            path.startsWith("/v1/melt/quote/bolt11/") -> ok(json.encodeToString(MeltQuote.serializer(), meltQuotes.getValue(path.substringAfterLast('/'))))
            path == "/v1/melt/bolt11" -> {
                if (refuseMelt) return refuse("Lightning backend refused", 20000)
                val id = body.getValue("quote").jsonPrimitive.content
                val quote = meltQuotes.getValue(id)
                val inputs = list("inputs", Proof.serializer())
                val outputs = list("outputs", BlindedMessage.serializer())
                check(inputs)?.let { return refuse(it, 11001) }
                if (inputs.sumOf { it.amount } - fee(inputs) < quote.amount + quote.feeReserve) return refuse("not enough inputs", 11005)
                meltQuotes[id] = when (meltOutcome) {
                    "PAID" -> { spentYs += inputs.map(Coins::y); pay(quote, inputs, outputs) }
                    "PENDING" -> { pendingYs += inputs.map(Coins::y); pendingMelt[id] = inputs to outputs; quote.copy(state = "PENDING") }
                    else -> quote
                }
                if (dropAfterMelt) throw IOException("connection reset")
                ok(json.encodeToString(MeltQuote.serializer(), meltQuotes.getValue(id)))
            }
            path == "/v1/checkstate" -> {
                val ys = body.getValue("Ys").jsonArray.map { it.jsonPrimitive.content }
                ok(buildJsonObject {
                    put("states", kotlinx.serialization.json.JsonArray(ys.map { y ->
                        buildJsonObject { put("Y", y); put("state", if (y in spentYs) "SPENT" else if (y in pendingYs) "PENDING" else "UNSPENT") }
                    }))
                }.toString())
            }
            path == "/v1/restore" -> {
                val found = list("outputs", BlindedMessage.serializer()).filter { it.b in signed }
                ok(json.encodeToString(RestoreAnswer.serializer(), RestoreAnswer(found, found.map { signed.getValue(it.b) })))
            }
            else -> respond("not found", HttpStatusCode.NotFound)
        }
    }

    private fun pay(quote: MeltQuote, inputs: List<Proof>, blanks: List<BlindedMessage>): MeltQuote {
        spentYs += inputs.map(Coins::y)
        val change = inputs.sumOf { it.amount } - fee(inputs) - quote.amount - lightningFee
        val signatures = Coins.split(change).zip(blanks) { amount, blank -> keyset.sign(blank, amount).also { signed[blank.b] = it } }
        return quote.copy(state = "PAID", paymentPreimage = preimage, change = signatures)
    }

    private fun fee(inputs: List<Proof>) = Coins.fee(inputs, mapOf(keyset.id to feePpk))

    private fun check(inputs: List<Proof>): String? = when {
        inputs.isEmpty() -> "no inputs"
        !inputs.all(keyset::accepts) -> "invalid proof"
        inputs.any { Coins.y(it) in spentYs } -> "Token already spent."
        inputs.any { Coins.y(it) in pendingYs } -> "Token is pending."
        else -> null
    }

    private fun signatures(outputs: List<BlindedMessage>): String =
        json.encodeToString(SignaturesAnswer.serializer(), SignaturesAnswer(outputs.map { o -> keyset.sign(o).also { signed[o.b] = it } }))

    @Serializable private data class KeysAnswer(val keysets: List<MintKeyset>)
    @Serializable private data class SignaturesAnswer(val signatures: List<BlindSignature>)
    @Serializable private data class RestoreAnswer(val outputs: List<BlindedMessage>, val signatures: List<BlindSignature>)
}
