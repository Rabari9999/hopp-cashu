package space.sovereigncast.hopp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSBundle
import platform.Foundation.NSUserDefaults
import space.sovereigncast.hopp.ui.components.Languages

/**
 * Hopp's language, chosen in Hopp (maintainer 2026-11-09). The Settings app shows its per-app
 * language row only to whom the iPhone has two system languages or more; with one, Hopp's entry
 * led to a page without it. The choice is what that row writes, `AppleLanguages` in Hopp's own
 * defaults, so either way of choosing shows in the other; Hopp is drawn again in it at once
 * ([MainViewController]), the rest of iOS — notifications, the share sheet — takes it at the next start.
 */
internal object IosLanguage {
    private const val KEY = "AppleLanguages"
    private val defaults get() = NSUserDefaults.standardUserDefaults

    private val _chosen = MutableStateFlow(read())
    /** A language of [Languages], null for the device's. */
    val chosen: StateFlow<String?> = _chosen

    /** Hopp's own domain only: the device's languages are there too, merged, under the same key. */
    private fun read(): String? {
        val domain = NSBundle.mainBundle.bundleIdentifier?.let { defaults.persistentDomainForName(it) } ?: return null
        val first = (domain[KEY] as? List<*>)?.firstOrNull() as? String ?: return null
        return Languages.of(first).code.takeIf { first.lowercase().startsWith(it) }
    }

    fun choose(code: String?) {
        val known = code?.takeIf { c -> Languages.all.any { it.code == c } }
        if (known == null) defaults.removeObjectForKey(KEY) else defaults.setObject(listOf(known), KEY)
        _chosen.value = known
    }
}
