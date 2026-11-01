package space.sovereigncast.hopp.nostr

private fun nfkc(text: String): String = js("text.normalize('NFKC')")

actual fun normalizeNfkc(text: String): String = nfkc(text)
