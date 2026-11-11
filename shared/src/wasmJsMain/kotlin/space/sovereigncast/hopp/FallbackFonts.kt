package space.sovereigncast.hopp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import kotlinx.coroutines.await
import kotlin.js.Promise

// The file as one string, a character per byte (latin1): one hand-over to Kotlin instead of one call
// per byte — ~2 million for the emoji font, on the main thread right after the first frame (review 2026-10-31).
private fun fetchLatin1(url: String): Promise<JsString> = js("""fetch(url)
  .then(r => r.ok ? r.arrayBuffer() : Promise.reject(new Error('' + r.status)))
  .then(b => { const a = new Uint8Array(b); let s = ''; for (let i = 0; i < a.length; i += 32768) s += String.fromCharCode.apply(null, a.subarray(i, i + 32768)); return s; })""")

// The symbols before the emoji: Hopp's own ⚡ and ✓ stay text, drawn in the text's colour; an emoji
// asked for as one (✌️, ❤️ with U+FE0F) still comes in colour.
private val FONTS = listOf("hopp_fallback_symbols", "hopp_fallback_math", "hopp_fallback_cjk", "hopp_fallback_currency", "hopp_fallback_emoji")

/**
 * Symbols, emoji and the few other scripts Figtree lacks (resources/fonts), handed to
 * Skia as fallbacks once the first frame is drawn — before, a ⚡ or a ✓ is an empty box.
 */
@Composable
fun FallbackFonts() {
    val resolver = LocalFontFamilyResolver.current
    LaunchedEffect(Unit) {
        for (name in FONTS) runCatching {
            val text = fetchLatin1("fonts/$name.ttf").await<JsString>().toString()
            val bytes = ByteArray(text.length) { text[it].code.toByte() }
            resolver.preload(FontFamily(Font(name, bytes)))
        }
    }
}
