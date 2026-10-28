package space.sovereigncast.hopp

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

private fun clipboard(text: String): Unit = js("{ navigator.clipboard && navigator.clipboard.writeText(text).catch(() => {}); }")

private fun share(text: String, title: String): Unit =
    js("{ if (navigator.share) navigator.share({ title: title, text: text }).catch(() => {}); else if (navigator.clipboard) navigator.clipboard.writeText(text).catch(() => {}); }")

private fun openTab(url: String): Unit = js("{ window.open(url, '_blank', 'noopener,noreferrer'); }")

/** What the browser can do for the shared UI (ADR 0022): files by picker and download, the
 *  system's share where there is one, the clipboard — no downloads of episodes (ADR 0022, 1). */
class WebPlatformActions : PlatformActions {
    override val canDownload: Boolean get() = false
    override fun pickOpml(onText: (String) -> Unit) = pickTextFile(".opml,.xml,text/xml,text/x-opml,application/xml", onText)
    override fun exportOpml(text: String) = saveText("hopp-subscriptions.opml", "text/x-opml", text)
    override fun shareText(text: String, subject: String) = share(text, subject)
    override fun copyText(text: String, label: String) = clipboard(text)
    override fun openNostr(entity: String) = openTab("https://njump.me/$entity")
}
