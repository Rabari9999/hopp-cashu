package space.sovereigncast.hopp.value.cashu

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.crypto.toHex
import kotlin.io.encoding.Base64

/** The mint's DLEQ proof carried with a proof (NUT-12), hex. */
@Serializable
data class ProofDleq(val e: String, val s: String, val r: String)

/**
 * One ecash note (NUT-00): [amount] in the keyset's unit, signed by keyset [id].
 * [secret] and [c] together are the bearer value — whoever holds them can spend.
 */
@Serializable
data class Proof(
    val amount: Long,
    val id: String,
    val secret: String,
    @SerialName("C") val c: String,
    val dleq: ProofDleq? = null,
    val witness: String? = null,
)

/** Proofs from one mint, as passed between people: `cashuB…` (v4) or the older `cashuA…` (v3). */
data class CashuToken(val mint: String, val proofs: List<Proof>, val unit: String? = null, val memo: String? = null) {
    val amount: Long get() = proofs.sumOf { it.amount }
}

/** NUT-00 token serialization. Hopp writes v4 and reads both. */
object CashuTokens {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    @Serializable
    private data class V3(val token: List<V3Entry>, val unit: String? = null, val memo: String? = null)

    @Serializable
    private data class V3Entry(val mint: String, val proofs: List<Proof>)

    /** Throws IllegalArgumentException for anything that is not a single-mint token. */
    fun decode(text: String): CashuToken {
        val token = text.trim().removePrefix("cashu:")
        require(token.length > 6 && token.startsWith("cashu")) { "not a Cashu token" }
        val body = try {
            base64.decode(token.substring(6).replace('+', '-').replace('/', '_').trimEnd('='))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Cashu token: bad base64", e)
        }
        return when (token[5]) {
            'A' -> decodeV3(body)
            'B' -> decodeV4(body)
            else -> throw IllegalArgumentException("Cashu token: unknown version ${token[5]}")
        }
    }

    fun encodeV4(token: CashuToken): String = "cashuB" + base64.encode(Cbor.encode(v4Map(token)))

    fun encodeV3(token: CashuToken): String =
        "cashuA" + base64.encode(json.encodeToString(V3.serializer(), V3(listOf(V3Entry(token.mint, token.proofs)), token.unit, token.memo)).encodeToByteArray())

    private fun decodeV3(body: ByteArray): CashuToken {
        val v3 = try {
            json.decodeFromString(V3.serializer(), body.decodeToString())
        } catch (e: Exception) {
            throw IllegalArgumentException("Cashu token: bad v3 JSON", e)
        }
        val mints = v3.token.map { it.mint }.distinct()
        require(mints.size == 1) { "Cashu token: ${mints.size} mints, one supported" }
        return CashuToken(mints.single(), v3.token.flatMap { it.proofs }, v3.unit, v3.memo).validated()
    }

    // Field names and their order follow the NUT-00 vectors: t, d, m, u; i, p; a, s, c, d, w.
    internal fun v4Map(token: CashuToken): Map<String, Any?> = buildMap {
        put("t", token.proofs.groupBy { it.id }.map { (id, proofs) ->
            mapOf(
                "i" to id.hexToBytes(),
                "p" to proofs.map { p ->
                    buildMap {
                        put("a", p.amount)
                        put("s", p.secret)
                        put("c", p.c.hexToBytes())
                        p.dleq?.let { put("d", mapOf("e" to it.e.hexToBytes(), "s" to it.s.hexToBytes(), "r" to it.r.hexToBytes())) }
                        p.witness?.let { put("w", it) }
                    }
                },
            )
        })
        token.memo?.let { put("d", it) }
        put("m", token.mint)
        token.unit?.let { put("u", it) }
    }

    private fun decodeV4(body: ByteArray): CashuToken {
        val root = Cbor.decode(body) as? Map<*, *> ?: throw IllegalArgumentException("Cashu token: v4 is no map")
        val proofs = root.list("t").flatMap { entry ->
            val keyset = (entry as? Map<*, *>)?.bytes("i")?.toHex() ?: throw IllegalArgumentException("Cashu token: bad keyset entry")
            entry.list("p").map { raw ->
                val p = raw as? Map<*, *> ?: throw IllegalArgumentException("Cashu token: bad proof")
                Proof(
                    amount = p["a"] as? Long ?: throw IllegalArgumentException("Cashu token: proof amount"),
                    id = keyset,
                    secret = p["s"] as? String ?: throw IllegalArgumentException("Cashu token: proof secret"),
                    c = p.bytes("c").toHex(),
                    dleq = (p["d"] as? Map<*, *>)?.let { ProofDleq(it.bytes("e").toHex(), it.bytes("s").toHex(), it.bytes("r").toHex()) },
                    witness = p["w"] as? String,
                )
            }
        }
        val mint = root["m"] as? String ?: throw IllegalArgumentException("Cashu token: no mint")
        return CashuToken(mint, proofs, root["u"] as? String, root["d"] as? String).validated()
    }

    private fun Map<*, *>.list(key: String): List<*> = this[key] as? List<*> ?: throw IllegalArgumentException("Cashu token: no $key")

    private fun Map<*, *>.bytes(key: String): ByteArray = this[key] as? ByteArray ?: throw IllegalArgumentException("Cashu token: no $key")

    private fun CashuToken.validated(): CashuToken {
        require(proofs.isNotEmpty()) { "Cashu token: no proofs" }
        require(proofs.all { it.amount > 0 }) { "Cashu token: non-positive amount" }
        require(mint.startsWith("https://") || mint.startsWith("http://")) { "Cashu token: mint is no URL" }
        return this
    }
}
