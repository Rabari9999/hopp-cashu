package space.sovereigncast.hopp.crypto

private const val HEX = "0123456789abcdef"

fun ByteArray.toHex(): String = buildString(size * 2) {
    for (b in this@toHex) { append(HEX[b.toInt() shr 4 and 0xF]); append(HEX[b.toInt() and 0xF]) }
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { i ->
        val hi = HEX.indexOf(this[2 * i].lowercaseChar()); val lo = HEX.indexOf(this[2 * i + 1].lowercaseChar())
        require(hi >= 0 && lo >= 0) { "not hex: $this" }
        ((hi shl 4) or lo).toByte()
    }
}

/** Cryptographically secure random bytes from the platform. */
expect fun secureRandomBytes(size: Int): ByteArray
