package space.sovereigncast.hopp.nostr

import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCompatibilityMapping

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun normalizeNfkc(text: String): String = (text as NSString).precomposedStringWithCompatibilityMapping
