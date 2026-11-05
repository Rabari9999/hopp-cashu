package space.sovereigncast.hopp

import kotlinx.coroutines.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import space.sovereigncast.hopp.web.WebBuild
import kotlin.js.Promise

// The service worker (sw.js, ADR 0022, 14): registered once the tab holds Hopp; a build that
// waits says its version, and the page reloads into it only when the listener takes it. A tab
// open for long asks the server for one every hour.
private fun registerWorker(onReady: (String) -> Unit): Unit = js("""{
  if (!('serviceWorker' in navigator)) return;
  const sw = navigator.serviceWorker;
  sw.addEventListener('message', (e) => { if (e.data && e.data.type === 'hopp:ready' && sw.controller) onReady(String(e.data.version)); });
  sw.addEventListener('controllerchange', () => { if (window.__hoppUpdating) location.reload(); });
  sw.register('sw.js').then((reg) => {
    const ask = (w) => { if (w && sw.controller) w.postMessage('hopp:version'); };
    ask(reg.waiting);
    reg.addEventListener('updatefound', () => {
      const w = reg.installing;
      if (w) w.addEventListener('statechange', () => { if (w.state === 'installed') ask(w); });
    });
    setInterval(() => reg.update().catch(() => {}), 3600000);
  }).catch(() => {});
}""")

private fun takeWaiting(): Unit = js("""{
  window.__hoppUpdating = true;
  navigator.serviceWorker.getRegistration().then((reg) => { if (reg && reg.waiting) reg.waiting.postMessage('hopp:update'); else location.reload(); });
}""")

// This build: release.json as the page got it (from the service worker's copy once it serves the
// page) — its version and SHA-256. Empty where there is none (a build not made by release.mjs).
private fun readBuild(): Promise<JsString> = js("""
  fetch('release.json', { cache: 'no-cache' })
    .then((r) => r.ok ? r.arrayBuffer() : Promise.reject())
    .then(async (bytes) => {
      const hash = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)), (b) => b.toString(16).padStart(2, '0')).join('');
      return JSON.stringify({ version: JSON.parse(new TextDecoder().decode(bytes)).version, hash });
    })
    .catch(() => '')
""")

internal object WebUpdates {
    private val _ready = MutableStateFlow<String?>(null)
    /** The version of a build waiting to take over. */
    val ready: StateFlow<String?> = _ready.asStateFlow()

    fun start() = registerWorker { _ready.value = it }

    fun apply() = takeWaiting()

    suspend fun current(): WebBuild? {
        val text = readBuild().await<JsString>().toString().ifEmpty { return null }
        val o = kotlinx.serialization.json.Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject ?: return null
        val version = (o["version"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
        val hash = (o["hash"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
        return WebBuild(version, hash)
    }
}
