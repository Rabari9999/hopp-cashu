package space.sovereigncast.hopp.value

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.crypto.Sha256
import space.sovereigncast.hopp.crypto.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LnAddressTest {

    // ── BOLT11, against the vectors of the BOLT 11 spec ────────────────────
    @Test
    fun specVectors() {
        val donation = Bolt11.parse("lnbc1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq9qrsgq357wnc5r2ueh7ck6q93dj32dlqnls087fxdwk8qakdyafkq3yap9us6v52vjjsrvywa6rt52cm9r9zqt8r2t7mlcwspyetp5h2tztugp9lfyql")
        assertNull(donation.amountMsat)
        assertEquals("0001020304050607080900010203040506070809000102030405060708090102", donation.paymentHash)

        val coffee = Bolt11.parse("lnbc2500u1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpu9qrsgquk0rl77nj30yxdy8j9vdx85fkpmdla2087ne0xh8nhedh8w27kyke0lp53ut353s06fv3qfegext0eh0ymjpf39tuven09sam30g4vgpfna3rh")
        assertEquals(250_000_000L, coffee.amountMsat)

        val hashed = Bolt11.parse("lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqs9qrsgq7ea976txfraylvgzuxs8kgcw23ezlrszfnh8r6qtfpr6cxga50aj6txm9rxrydzd06dfeawfk6swupvz4erwnyutnjq7x39ymw6j38gp7ynn44")
        assertEquals(2_000_000_000L, hashed.amountMsat)
        assertEquals("3925b6f67e2c340036ed12093dd44e0368df1b6ea26c53dbe4811f58fd5db8c1", hashed.descriptionHashHex)
    }

    @Test
    fun invalidInvoicesAreRefused() {
        // Spec "invalid" examples: bad checksum, no separator, mixed case, too short,
        // unknown multiplier, sub-millisatoshi amount.
        for (bad in listOf(
            "lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpquwpc4curk03c9wlrswe78q4eyqc7d8d0xqzpuyk0sg5g70me25alkluzd2x62aysf2pyy8edtjeevuv4p2d5p76r4zkmneet7uvyakky2zr4cusd45tftc9c5fh0nnqpnl2jfll544esqchsrnt",
            "pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpquwpc4curk03c9wlrswe78q4eyqc7d8d0xqzpuyk0sg5g70me25alkluzd2x62aysf2pyy8edtjeevuv4p2d5p76r4zkmneet7uvyakky2zr4cusd45tftc9c5fh0nnqpnl2jfll544esqchsrny",
            "LNBC2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpquwpc4curk03c9wlrswe78q4eyqc7d8d0xqzpuyk0sg5g70me25alkluzd2x62aysf2pyy8edtjeevuv4p2d5p76r4zkmneet7uvyakky2zr4cusd45tftc9c5fh0nnqpnl2jfll544esqchsrny",
            "lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6na6hlh",
            "lnbc2500x1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpusp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9qrsgqrrzc4cvfue4zp3hggxp47ag7xnrlr8vgcmkjxk3j5jqethnumgkpqp23z9jclu3v0a7e0aruz366e9wqdykw6dxhdzcjjhldxq0w6wgqcnu43j",
            "lnbc2500000001p1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpusp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9qrsgq0lzc236j96a95uv0m3umg28gclm5lqxtqqwk32uuk4k6673k6n5kfvx3d2h8s295fad45fdhmusm8sjudfhlf6dcsxmfvkeywmjdkxcp99202x",
        )) assertFailsWith<IllegalArgumentException>(bad.take(20)) { Bolt11.parse(bad) }
    }

    // ── LNURL-pay ───────────────────────────────────────────────────────────
    private val metadata = """[["text/plain","Pay host"],["text/identifier","host@wallet.test"]]"""

    private fun client(invoiceFor: (Long) -> String): HttpClient = HttpClient(MockEngine { request ->
        val url = request.url
        val json = HttpHeaders.ContentType to listOf("application/json")
        when {
            url.encodedPath == "/.well-known/lnurlp/host" -> respond(
                """{"tag":"payRequest","callback":"https://wallet.test/cb/host","minSendable":1000,"maxSendable":100000000,"metadata":${kotlinx.serialization.json.JsonPrimitive(metadata)},"commentAllowed":144}""",
                headers = headersOf(json))
            url.encodedPath == "/cb/host" -> respond("""{"pr":"${invoiceFor(url.parameters["amount"]!!.toLong())}","routes":[]}""", headers = headersOf(json))
            url.encodedPath == "/.well-known/keysend/host" -> respond(
                """{"status":"OK","tag":"keysend","pubkey":"03abc","customData":[{"customKey":"696969","customValue":"42"}]}""",
                headers = headersOf(json))
            else -> respond("", HttpStatusCode.NotFound)
        }
    })

    @Test
    fun anInvoiceForExactlyTheAmountIsAccepted() = runBlocking {
        val hash = Sha256.digest(metadata.encodeToByteArray())
        val ln = LnAddressClient(client { msat -> TestInvoices.make(msat, hash) })
        val request = ln.payRequest("host@wallet.test")
        assertEquals(144, request.commentAllowed)
        val pr = ln.invoice(request, 21_000, "rss::payment::boost Danke")
        assertEquals(21_000L, Bolt11.parse(pr).amountMsat)
    }

    @Test
    fun anInvoiceForADifferentAmountIsRefused() = runBlocking {
        val hash = Sha256.digest(metadata.encodeToByteArray())
        val ln = LnAddressClient(client { msat -> TestInvoices.make(msat * 10, hash) })
        val e = assertFailsWith<LnAddressException> { ln.invoice(ln.payRequest("host@wallet.test"), 21_000, null) }
        println(e.message)
    }

    @Test
    fun anInvoiceThatDoesNotCommitToTheMetadataIsRefused() = runBlocking {
        val ln = LnAddressClient(client { msat -> TestInvoices.make(msat, ByteArray(32)) })
        assertFailsWith<LnAddressException> { ln.invoice(ln.payRequest("host@wallet.test"), 21_000, null) }
        Unit
    }

    @Test
    fun theKeysendRouteOfALightningAddress() = runBlocking {
        val ln = LnAddressClient(client { "" })
        val info = ln.keysendInfo("host@wallet.test")!!
        assertEquals("03abc", info.pubkey)
        assertEquals("696969" to "42", info.customKey to info.customValue)
        assertNull(ln.keysendInfo("nobody@wallet.test"))
    }
}

/** Builds structurally valid (unsigned) BOLT11 strings for tests: correct
 *  bech32 checksum, amount and `h` field; the signature is zeros. */
object TestInvoices {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    fun make(amountMsat: Long, descriptionHash: ByteArray): String {
        val hrp = "lnbc" + (amountMsat * 10) + "p"
        val words = mutableListOf<Int>()
        repeat(7) { words += 0 }                                   // timestamp
        fun field(tag: Char, bytes: ByteArray) {
            val w = toWords(bytes); words += CHARSET.indexOf(tag); words += w.size / 32; words += w.size % 32; words += w
        }
        field('p', ByteArray(32) { 1 })
        field('h', descriptionHash)
        repeat(104) { words += 0 }                                 // signature
        val checksum = checksum(hrp, words)
        return hrp + "1" + (words + checksum).joinToString("") { CHARSET[it].toString() }
    }

    private fun toWords(bytes: ByteArray): List<Int> {
        val out = mutableListOf<Int>(); var acc = 0; var bits = 0
        for (b in bytes) { acc = (acc shl 8) or (b.toInt() and 0xFF); bits += 8; while (bits >= 5) { bits -= 5; out += (acc shr bits) and 31 } }
        if (bits > 0) out += (acc shl (5 - bits)) and 31
        return out
    }

    private fun polymod(values: List<Int>): Int {
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) { val top = chk ushr 25; chk = (chk and 0x1ffffff shl 5) xor v; for (i in 0 until 5) if ((top ushr i) and 1 == 1) chk = chk xor gen[i] }
        return chk
    }

    private fun checksum(hrp: String, data: List<Int>): List<Int> {
        val values = hrp.map { it.code ushr 5 } + 0 + hrp.map { it.code and 31 } + data + List(6) { 0 }
        val mod = polymod(values) xor 1
        return (0 until 6).map { (mod ushr (5 * (5 - it))) and 31 }
    }

    @Suppress("unused") fun hex(b: ByteArray) = b.toHex()
}
