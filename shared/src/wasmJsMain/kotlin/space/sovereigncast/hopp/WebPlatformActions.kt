package space.sovereigncast.hopp

import space.sovereigncast.hopp.platform.TabOnly
import space.sovereigncast.hopp.ui.PlatformActions

private fun pickTextFile(accept: String, onText: (String) -> Unit): Unit = js("""{
  const input = document.createElement('input');
  input.type = 'file'; input.accept = accept;
  input.onchange = () => { const f = input.files && input.files[0]; if (f && f.size <= 16 * 1024 * 1024) f.text().then(t => onText(t)); };
  input.click();
}""")

private fun saveText(name: String, type: String, text: String): Unit = js("""{
  const url = URL.createObjectURL(new Blob([text], { type: type }));
  const a = document.createElement('a'); a.href = url; a.download = name; a.click();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
}""")

// A download where the audio's host allows it; where it does not (another origin), the browser
// opens the file in a tab of its own, with its player's save button — no audio through Hopp's proxy.
private fun saveLink(url: String, name: String): Unit = js("""{
  const a = document.createElement('a'); a.href = url; a.download = name; a.target = '_blank'; a.rel = 'noopener noreferrer'; a.click();
}""")

private fun clipboard(text: String): Unit = js("{ navigator.clipboard && navigator.clipboard.writeText(text).catch(() => {}); }")

private fun share(text: String, title: String): Unit =
    js("{ if (navigator.share) navigator.share({ title: title, text: text }).catch(() => {}); else if (navigator.clipboard) navigator.clipboard.writeText(text).catch(() => {}); }")

private fun openTab(url: String): Unit = js("{ window.open(url, '_blank', 'noopener,noreferrer'); }")

private fun storedLanguage(): String? = js("(() => { try { return localStorage.getItem('hopp-language'); } catch (e) { return null; } })()")
private fun storeLanguage(code: String?): Unit =
    js("{ try { if (code) localStorage.setItem('hopp-language', code); else localStorage.removeItem('hopp-language'); } catch (e) {} location.reload(); }")

// The page's language for Compose, which reads the browser's: the listener's choice, if any,
// put in before the app starts (Main.kt).
private fun overrideLanguage(code: String): Unit = js("""{
  Object.defineProperty(navigator, 'language', { get: () => code, configurable: true });
  Object.defineProperty(navigator, 'languages', { get: () => [code], configurable: true });
  document.documentElement.lang = code;
}""")

/** The language the listener chose in Hopp, in place of the browser's — before the app starts. */
internal fun applyChosenLanguage() { storedLanguage()?.takeIf { it in LANGUAGES }?.let(::overrideLanguage) }

private val LANGUAGES = mapOf("en" to "English", "de" to "Deutsch")

/** What the browser can do for the shared UI (ADR 0022): files by picker and download, the
 *  system's share where there is one, the clipboard — no downloads of episodes (ADR 0022, 1). */
class WebPlatformActions(override val tabOnly: TabOnly? = null) : PlatformActions {
    override val canDownload: Boolean get() = false
    // No wallet in the browser until ADR 0022, 4's stage W5 (maintainer 2026-10-30).
    override val canPay: Boolean get() = false
    override val canSaveAudio: Boolean get() = true
    override fun saveAudio(url: String, name: String) = saveLink(url, name)
    // Kept in localStorage and taken in by a reload, which a tab that keeps nothing does not survive.
    override val appLanguages: Map<String, String>? get() = LANGUAGES.takeUnless { tabOnly?.active?.value == true }
    override val appLanguage: String? get() = storedLanguage()?.takeIf { it in LANGUAGES }
    override fun setAppLanguage(code: String?) = storeLanguage(code)
    override fun pickOpml(onText: (String) -> Unit) = pickTextFile(".opml,.xml,text/xml,text/x-opml,application/xml", onText)
    override fun exportOpml(text: String) = saveText("hopp-subscriptions.opml", "text/x-opml", text)
    override fun shareText(text: String, subject: String) = share(text, subject)
    override fun copyText(text: String, label: String) = clipboard(text)
    override fun openNostr(entity: String) = openTab("https://njump.me/$entity")
}
