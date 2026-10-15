package space.sovereigncast.hopp.nostr

import java.text.Normalizer

actual fun normalizeNfkc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
