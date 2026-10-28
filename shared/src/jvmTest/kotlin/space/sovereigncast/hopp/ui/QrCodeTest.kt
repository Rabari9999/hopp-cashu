package space.sovereigncast.hopp.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.LuminanceSource
import space.sovereigncast.hopp.ui.components.QrCode
import kotlin.test.Test
import kotlin.test.assertEquals

/** What Hopp draws as a QR code reads back as what it encoded — checked with ZXing. */
class QrCodeTest {
    private fun decode(qr: QrCode, scale: Int = 4, quiet: Int = 4): String {
        val n = (qr.size + quiet * 2) * scale
        val pixels = ByteArray(n * n) { 0xFF.toByte() }
        for (y in 0 until qr.size) for (x in 0 until qr.size) if (qr[x, y]) {
            for (dy in 0 until scale) for (dx in 0 until scale) pixels[((y + quiet) * scale + dy) * n + (x + quiet) * scale + dx] = 0
        }
        val source = object : LuminanceSource(n, n) {
            override fun getRow(y: Int, row: ByteArray?): ByteArray = pixels.copyOfRange(y * n, (y + 1) * n)
            override fun getMatrix(): ByteArray = pixels
        }
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.PURE_BARCODE to false, DecodeHintType.TRY_HARDER to true)).text
    }

    @Test
    fun aNostrConnectAddressReadsBack() {
        val uri = "nostrconnect://" + "a1".repeat(32) + "?relay=wss%3A%2F%2Frelay.nsec.app&relay=wss%3A%2F%2Frelay.damus.io&secret=" +
            "f00d".repeat(8) + "&perms=sign_event%3A27235%2Csign_event%3A1%2Csign_event%3A1111%2Csign_event%3A7%2Cnip44_encrypt%2Cnip44_decrypt&name=Hopp"
        val qr = QrCode.encode(uri)
        assertEquals(uri, decode(qr))
    }

    @Test
    fun everySizeAndLevelReadsBack() {
        for (ecc in QrCode.Ecc.entries) for (length in listOf(1, 17, 50, 120, 300, 700, 1200)) {
            val chars = "Hopp ⚡ 0123456789abcdefghijklmnopqrstuvwxyz"
            val text = buildString { repeat(length) { append(chars[(it * 7 + length) % chars.length]) } }
            if (text.encodeToByteArray().size > 1200 && ecc == QrCode.Ecc.HIGH) continue
            val qr = QrCode.encode(text, ecc)
            assertEquals(text, decode(qr, scale = 3), "length $length, $ecc, version ${(qr.size - 17) / 4}")
        }
    }
}
