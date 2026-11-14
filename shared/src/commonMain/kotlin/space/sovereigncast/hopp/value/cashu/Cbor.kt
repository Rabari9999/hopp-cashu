package space.sovereigncast.hopp.value.cashu

/**
 * The slice of CBOR (RFC 8949) a Cashu v4 token uses: unsigned and negative
 * integers, byte and text strings, arrays, maps, true, false and null.
 * Definite lengths only on output; tags are read through. Decoded values are
 * Long, ByteArray, String, List, Map (in wire order), Boolean or null.
 */
internal object Cbor {
    fun encode(value: Any?): ByteArray = Writer().apply { write(value) }.bytes()

    fun decode(bytes: ByteArray): Any? {
        val reader = Reader(bytes)
        val value = reader.read(0)
        require(reader.atEnd()) { "cbor: trailing bytes" }
        return value
    }

    private class Writer {
        private val out = ArrayList<Byte>()

        fun bytes(): ByteArray = out.toByteArray()

        private fun head(major: Int, length: Long) {
            val m = major shl 5
            when {
                length < 24 -> out += (m or length.toInt()).toByte()
                length <= 0xff -> { out += (m or 24).toByte(); out += length.toByte() }
                length <= 0xffff -> { out += (m or 25).toByte(); be(length, 2) }
                length <= 0xffffffffL -> { out += (m or 26).toByte(); be(length, 4) }
                else -> { out += (m or 27).toByte(); be(length, 8) }
            }
        }

        private fun be(value: Long, size: Int) {
            for (i in size - 1 downTo 0) out += (value ushr (8 * i)).toByte()
        }

        fun write(value: Any?) {
            when (value) {
                null -> out += 0xf6.toByte()
                is Boolean -> out += (if (value) 0xf5 else 0xf4).toByte()
                is Int -> write(value.toLong())
                is Long -> if (value >= 0) head(0, value) else head(1, -1 - value)
                is ByteArray -> { head(2, value.size.toLong()); value.forEach { out += it } }
                is String -> { val utf8 = value.encodeToByteArray(); head(3, utf8.size.toLong()); utf8.forEach { out += it } }
                is List<*> -> { head(4, value.size.toLong()); value.forEach { write(it) } }
                is Map<*, *> -> { head(5, value.size.toLong()); value.forEach { (k, v) -> write(k); write(v) } }
                else -> throw IllegalArgumentException("cbor: cannot encode ${value::class.simpleName}")
            }
        }
    }

    private class Reader(private val bytes: ByteArray) {
        private var pos = 0

        fun atEnd() = pos == bytes.size

        private fun byte(): Int {
            require(pos < bytes.size) { "cbor: truncated" }
            return bytes[pos++].toInt() and 0xff
        }

        private fun argument(info: Int): Long = when {
            info < 24 -> info.toLong()
            info == 24 -> byte().toLong()
            info == 25 -> uint(2)
            info == 26 -> uint(4)
            info == 27 -> uint(8).also { require(it >= 0) { "cbor: integer too large" } }
            else -> throw IllegalArgumentException("cbor: indefinite or reserved length")
        }

        private fun uint(size: Int): Long {
            var v = 0L
            repeat(size) { v = (v shl 8) or byte().toLong() }
            return v
        }

        private fun length(info: Int): Int {
            val n = argument(info)
            require(n <= bytes.size - pos) { "cbor: length beyond input" }
            return n.toInt()
        }

        private fun take(n: Int): ByteArray = bytes.copyOfRange(pos, pos + n).also { pos += n }

        fun read(depth: Int): Any? {
            require(depth < 32) { "cbor: nested too deep" }
            val initial = byte()
            val info = initial and 0x1f
            return when (initial ushr 5) {
                0 -> argument(info)
                1 -> -1 - argument(info)
                2 -> take(length(info))
                3 -> take(length(info)).decodeToString(throwOnInvalidSequence = true)
                4 -> List(length(info)) { read(depth + 1) }
                5 -> {
                    val n = length(info)
                    LinkedHashMap<Any?, Any?>().apply { repeat(n) { val k = read(depth + 1); put(k, read(depth + 1)) } }
                }
                6 -> { argument(info); read(depth + 1) }
                else -> when (info) {
                    20 -> false
                    21 -> true
                    22, 23 -> null
                    else -> throw IllegalArgumentException("cbor: unsupported simple value $info")
                }
            }
        }
    }
}
