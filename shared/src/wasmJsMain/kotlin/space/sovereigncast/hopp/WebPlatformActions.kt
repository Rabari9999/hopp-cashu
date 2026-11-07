package space.sovereigncast.hopp

import space.sovereigncast.hopp.platform.TabOnly
import space.sovereigncast.hopp.ui.PlatformActions

private fun pickTextFile(accept: String, onText: (String) -> Unit): Unit = js("""{
  const input = document.createElement('input');
  input.type = 'file'; input.accept = accept;
  input.onchange = () => { const f = input.files && input.files[0]; if (f && f.size <= 16 * 1024 * 1024) f.text().then(t => onText(t)); };
  input.click();
}""")

// A picture, square and small, as a JPEG drawn anew on a canvas — none of the file's metadata comes along
// (createImageBitmap turns it upright first). Base64 across to Kotlin; nothing for a file too large or no picture.
private fun pickImageFile(size: Int, onImage: (String) -> Unit): Unit = js("""{
  const input = document.createElement('input');
  input.type = 'file'; input.accept = 'image/*';
  input.onchange = () => {
    const f = input.files && input.files[0];
    if (!f || f.size > 30 * 1024 * 1024) return;
    createImageBitmap(f, { imageOrientation: 'from-image' }).then(img => {
      const side = Math.min(img.width, img.height);
      const c = document.createElement('canvas'); c.width = size; c.height = size;
      const g = c.getContext('2d'); g.fillStyle = '#fff'; g.fillRect(0, 0, size, size);
      g.drawImage(img, (img.width - side) / 2, (img.height - side) / 2, side, side, 0, 0, size, size);
      c.toBlob(b => { if (!b) return; const r = new FileReader(); r.onload = () => onImage(String(r.result).split(',')[1] || ''); r.readAsDataURL(b); }, 'image/jpeg', 0.85);
    }).catch(() => {});
  };
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

private fun themeColor(color: String): Unit = js("""{ document.querySelectorAll('meta[name="theme-color"]').forEach(m => m.setAttribute('content', color)); }""")

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

// A phone or tablet, by what the browser says of itself — an iPad asks for the desktop site, so its touch screen tells.
private fun isMobileBrowser(): Boolean = js("/Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent) || (navigator.maxTouchPoints > 1 && /Macintosh/.test(navigator.userAgent))")

private val LANGUAGES = space.sovereigncast.hopp.ui.components.Languages.all.associate { it.code to it.name }

/** What the browser can do for the shared UI (ADR 0022): files by picker and download, the
 *  system's share where there is one, the clipboard — no downloads of episodes (ADR 0022, 1). */
class WebPlatformActions(override val tabOnly: TabOnly? = null) : PlatformActions {
    override val canDownload: Boolean get() = false
    // No wallet in the browser until ADR 0022, 4's stage W5 (maintainer 2026-10-30).
    // Paid from the browser's own wallet, an extension's (WebLN — ADR 0022, 4, stage W3).
    override val canPay: Boolean get() = true
    override val mobileBrowser: Boolean get() = isMobileBrowser()
    override val paysWithBrowser: Boolean get() = true
    override val canNotify: Boolean get() = false
    override val updateReady: kotlinx.coroutines.flow.StateFlow<String?> get() = WebUpdates.ready
    override fun applyUpdate() = WebUpdates.apply()
    override val canSaveAudio: Boolean get() = true
    override val refreshButton: Boolean get() = true
    override fun groundChosen(argb: Int) = themeColor("#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0'))
    override fun saveAudio(url: String, name: String) {
        // The feed's word, as a link the page clicks: a web address only, whatever the parser let through.
        if (url.startsWith("https://", true) || url.startsWith("http://", true)) saveLink(url, name)
    }
    // Kept in localStorage and taken in by a reload, which a tab that keeps nothing does not survive.
    override val appLanguages: Map<String, String>? get() = LANGUAGES.takeUnless { tabOnly?.active?.value == true }
    override val appLanguage: String? get() = storedLanguage()?.takeIf { it in LANGUAGES }
    override fun setAppLanguage(code: String?) = storeLanguage(code)
    override fun pickOpml(onText: (String) -> Unit) = pickTextFile(".opml,.xml,text/xml,text/x-opml,application/xml", onText)
    override fun exportOpml(text: String) = saveText("hopp-subscriptions.opml", "text/x-opml", text)
    override val canPickImage: Boolean get() = true
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    override fun pickProfileImage(onImage: (ByteArray) -> Unit) = pickImageFile(space.sovereigncast.hopp.ui.PROFILE_PICTURE_SIZE) { b64 ->
        runCatching { kotlin.io.encoding.Base64.decode(b64) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let(onImage)
    }
    override fun shareText(text: String, subject: String) = share(text, subject)
    override fun copyText(text: String, label: String) = clipboard(text)
    override fun openNostr(entity: String) = openTab("https://njump.me/$entity")
}
