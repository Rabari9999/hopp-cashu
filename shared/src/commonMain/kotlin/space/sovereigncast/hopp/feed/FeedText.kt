package space.sovereigncast.hopp.feed

/**
 * Bytes of a feed → text the XML reader can take.
 *
 * Real feeds lie about their encoding often enough that this cannot be left
 * to the reader: a UTF-8 declaration over Latin-1 bytes, a BOM followed by
 * blank lines before `<?xml`, UTF-16 from an old Windows tool. Order: BOM,
 * then the declaration, then strict UTF-8 with Windows-1252 as the fallback
 * (a superset of Latin-1 for every printable byte).
 */
internal object FeedText {

    data class Decoded(val text: String, val warnings: List<String>)

    fun decode(bytes: ByteArray): Decoded {
        val warnings = mutableListOf<String>()
        var start = 0
        var text: String? = null

        when {
            bytes.startsWith(0xEF, 0xBB, 0xBF) -> start = 3
            bytes.startsWith(0xFF, 0xFE) -> text = decodeUtf16(bytes, 2, littleEndian = true)
            bytes.startsWith(0xFE, 0xFF) -> text = decodeUtf16(bytes, 2, littleEndian = false)
        }

        if (text == null) {
            val declared = declaredEncoding(bytes, start)?.lowercase()
            text = when (declared) {
                null, "utf-8", "utf8" -> decodeUtf8OrFallback(bytes, start, warnings, declared != null)
                "iso-8859-1", "latin1", "latin-1", "us-ascii", "ascii" -> decodeWindows1252(bytes, start)
                "windows-1252", "cp1252" -> decodeWindows1252(bytes, start)
                else -> {
                    warnings += "unsupported encoding \"$declared\" — read as UTF-8"
                    decodeUtf8OrFallback(bytes, start, warnings, true)
                }
            }
        }

        // Anything before "<" makes the document invalid XML (a BOM decoded as
        // text, blank lines a CMS emitted). Dropping it changes no content.
        val firstTag = text.indexOf('<')
        if (firstTag > 0 && text.substring(0, firstTag).isBlank().not()) {
            warnings += "text before the first tag removed"
        }
        if (firstTag > 0) text = text.substring(firstTag)
        if (text.startsWith("﻿")) text = text.substring(1)
        return Decoded(text, warnings)
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { (this[it].toInt() and 0xFF) == prefix[it] }

    /** `encoding="…"` from the XML declaration, read as ASCII. */
    private fun declaredEncoding(bytes: ByteArray, start: Int): String? {
        val end = minOf(bytes.size, start + 512)
        val head = buildString { for (i in start until end) append((bytes[i].toInt() and 0x7F).toChar()) }
        val declEnd = head.indexOf("?>")
        if (!head.trimStart().startsWith("<?xml") || declEnd < 0) return null
        val match = Regex("""encoding\s*=\s*["']([A-Za-z0-9._-]+)["']""").find(head.substring(0, declEnd))
        return match?.groupValues?.get(1)
    }

    private fun decodeUtf8OrFallback(bytes: ByteArray, start: Int, warnings: MutableList<String>, declared: Boolean): String =
        try {
            bytes.decodeToString(start, bytes.size, throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            warnings += if (declared) "declared UTF-8 but is not — read as Windows-1252"
            else "not UTF-8 — read as Windows-1252"
            decodeWindows1252(bytes, start)
        }

    private fun decodeUtf16(bytes: ByteArray, start: Int, littleEndian: Boolean): String {
        val chars = CharArray((bytes.size - start) / 2)
        for (i in chars.indices) {
            val a = bytes[start + 2 * i].toInt() and 0xFF
            val b = bytes[start + 2 * i + 1].toInt() and 0xFF
            chars[i] = (if (littleEndian) (b shl 8) or a else (a shl 8) or b).toChar()
        }
        return chars.concatToString()
    }

    // 0x80–0x9F in Windows-1252; everything else maps 1:1 to Latin-1.
    private val CP1252_HIGH = charArrayOf(
        '€', '\u0081', '‚', 'ƒ', '„', '…', '†', '‡',
        'ˆ', '‰', 'Š', '‹', 'Œ', '\u008D', 'Ž', '\u008F',
        '\u0090', '‘', '’', '“', '”', '•', '–', '—',
        '˜', '™', 'š', '›', 'œ', '\u009D', 'ž', 'Ÿ',
    )

    private fun decodeWindows1252(bytes: ByteArray, start: Int): String {
        val chars = CharArray(bytes.size - start)
        for (i in chars.indices) {
            val b = bytes[start + i].toInt() and 0xFF
            chars[i] = if (b in 0x80..0x9F) CP1252_HIGH[b - 0x80] else b.toChar()
        }
        return chars.concatToString()
    }
}
