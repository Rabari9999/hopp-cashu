package space.sovereigncast.hopp

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import space.sovereigncast.hopp.di.sharedModule
import space.sovereigncast.hopp.platform.MemorySecretStore
import space.sovereigncast.hopp.platform.SecretStore
import space.sovereigncast.hopp.platform.WebSecretStore
import space.sovereigncast.hopp.ui.App
import kotlin.js.Promise

// One tab at a time: the database's file in OPFS opens in one worker only. The lock is held
// while the tab lives; where the browser has no Web Locks, nothing is held (and nothing checked).
private fun holdTabLock(): Promise<JsBoolean> = js("""navigator.locks
  ? new Promise(ok => navigator.locks.request('hopp-database', { ifAvailable: true }, lock => { ok(!!lock); return lock ? new Promise(() => {}) : null; }))
  : Promise.resolve(true)""")

private fun showOtherTab(): Unit = js("""{
  const de = (navigator.language || '').toLowerCase().startsWith('de');
  document.body.innerHTML = '<div style="font: 16px/1.5 system-ui, sans-serif; max-width: 28em; margin: 20vh auto; padding: 0 24px; text-align: center; color: #1a1a1a">' +
    (de ? '<p><b>Hopp ist schon in einem anderen Tab offen.</b></p><p>Schließ ihn oder wechsle dorthin.</p>'
        : '<p><b>Hopp is open in another tab.</b></p><p>Close it, or switch to it.</p>') + '</div>';
}""")

// Signing out in the browser takes everything Hopp kept there (ADR 0022, 2). The database's file
// is held by its worker while the app runs, so it goes on the next start, before the worker opens.
private fun markWipe(): Unit = js("{ try { localStorage.setItem('hopp-wipe', '1'); } catch (e) {} location.reload(); }")

private fun wipeIfMarked(): Promise<JsBoolean> = js("""(async () => {
  let marked = false;
  try { marked = localStorage.getItem('hopp-wipe') === '1'; } catch (e) {}
  if (!marked) return false;
  try { const root = await navigator.storage.getDirectory(); for await (const [name] of root.entries()) await root.removeEntry(name, { recursive: true }); } catch (e) {}
  await new Promise(ok => { const r = indexedDB.deleteDatabase('hopp-secrets'); r.onsuccess = r.onerror = r.onblocked = () => ok(); });
  try { localStorage.removeItem('hopp-wipe'); } catch (e) {}
  return true;
})()""")

internal fun wipeOnNextStart() = markWipe()

/** Hopp in the browser (ADR 0022, stage W2): the database first, then the app as on the phone. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    MainScope().launch {
        if (!holdTabLock().await<JsBoolean>().toBoolean()) return@launch showOtherTab()
        wipeIfMarked().await<JsBoolean>()
        val driver = openWebDatabase()
        // Without IndexedDB (some private windows) secrets live for this tab only.
        val secrets: SecretStore = try { WebSecretStore.open() } catch (e: Throwable) { MemorySecretStore() }
        startKoin { modules(sharedModule(WebPlatform(driver, secrets))) }
        ComposeViewport(document.body!!) {
            App(WebPlatformActions())
            FallbackFonts()
        }
    }
}
