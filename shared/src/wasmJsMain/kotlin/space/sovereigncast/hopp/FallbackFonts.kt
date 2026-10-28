package space.sovereigncast.hopp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import kotlinx.coroutines.await
import space.sovereigncast.hopp.crypto.Uint8Array
import space.sovereigncast.hopp.crypto.toByteArray
import kotlin.js.Promise

private fun fetchBytes(url: String): Promise<Uint8Array> =
    js("fetch(url).then(r => r.ok ? r.arrayBuffer() : Promise.reject(new Error('' + r.status))).then(b => new Uint8Array(b))")

private val FONTS = listOf("hopp_fallback_symbols", "hopp_fallback_math", "hopp_fallback_cjk", "hopp_fallback_emoji")

/**
 * Symbols, emoji and the few other scripts Figtree lacks (resources/fonts), handed to
 * Skia as fallbacks once the first frame is drawn — before, a ⚡ or a ✓ is an empty box.
 */
@Composable
fun FallbackFonts() {
    val resolver = LocalFontFamilyResolver.current
    LaunchedEffect(Unit) {
        for (name in FONTS) runCatching {
            val bytes = fetchBytes("fonts/$name.ttf").await<Uint8Array>().toByteArray()
            resolver.preload(FontFamily(Font(name, bytes)))
        }
    }
}
