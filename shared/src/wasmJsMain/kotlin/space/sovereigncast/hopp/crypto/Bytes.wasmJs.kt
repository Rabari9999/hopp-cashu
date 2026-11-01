package space.sovereigncast.hopp.crypto

/** The browser's typed array. Kotlin/Wasm keeps a ByteArray in the Wasm GC heap, so bytes are copied across. */
internal external class Uint8Array(length: Int) : JsAny {
    val length: Int
}

private fun get(a: Uint8Array, i: Int): Int = js("a[i]")
private fun set(a: Uint8Array, i: Int, v: Int): Unit = js("a[i] = v & 0xff")

internal fun ByteArray.toUint8Array(): Uint8Array = Uint8Array(size).also { a -> for (i in indices) set(a, i, this[i].toInt()) }
internal fun Uint8Array.toByteArray(): ByteArray = ByteArray(length) { get(this, it).toByte() }

private fun fillRandom(a: Uint8Array): Unit = js("globalThis.crypto.getRandomValues(a)")

actual fun secureRandomBytes(size: Int): ByteArray = Uint8Array(size).also { fillRandom(it) }.toByteArray()
