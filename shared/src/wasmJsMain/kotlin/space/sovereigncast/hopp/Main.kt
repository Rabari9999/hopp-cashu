package space.sovereigncast.hopp

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
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
private fun reload(): Unit = js("location.reload()")

private fun wipeIfMarked(): Promise<JsBoolean> = js("""(async () => {
  let marked = false;
  try { marked = localStorage.getItem('hopp-wipe') === '1'; } catch (e) {}
  if (!marked) return false;
  try { const root = await navigator.storage.getDirectory(); for await (const [name] of root.entries()) await root.removeEntry(name, { recursive: true }); } catch (e) {}
  await new Promise(ok => { const r = indexedDB.deleteDatabase('hopp-secrets'); r.onsuccess = r.onerror = r.onblocked = () => ok(); });
  try { localStorage.removeItem('hopp-wipe'); } catch (e) {}
  return true;
})()""")

// Where the start's time goes, for the browser's developer tools (Performance, User Timing).
private fun mark(name: String): Unit = js("{ try { performance.mark(name); } catch (e) {} }")
private fun hideSplash(): Unit = js("""{ const s = document.getElementById('hopp-splash'); if (s) { s.style.opacity = '0'; setTimeout(() => s.remove(), 250); } }""")

// A tab that keeps nothing stored nothing: a reload starts it afresh, and what is stored in the
// browser — another listener's — stays.
internal fun wipeOnNextStart() = if (tabOnlyMarked()) reload() else markWipe()

/** Hopp in the browser (ADR 0022, stage W2): the database first, then the app as on the phone. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    mark("hopp:main")
    MainScope().launch {
        if (!holdTabLock().await<JsBoolean>().toBoolean()) return@launch showOtherTab()
        mark("hopp:tab-lock")
        wipeIfMarked().await<JsBoolean>()
        mark("hopp:wipe-checked")
        // "This is not my device", chosen earlier in this tab: nothing opened that is stored.
        val tabOnly = tabOnlyMarked()
        val driver = openWebDatabase(tabOnly)
        mark("hopp:database")
        // Without IndexedDB (some private windows) secrets live for this tab only.
        val secrets: SecretStore = if (tabOnly) MemorySecretStore() else try { WebSecretStore.open() } catch (e: Throwable) { MemorySecretStore() }
        applyChosenLanguage()
        startKoin { modules(sharedModule(WebPlatform(driver, secrets))) }
        val actions = WebPlatformActions(WebTabOnly(driver, secrets, tabOnly))
        mark("hopp:compose")
        ComposeViewport(document.body!!) {
            App(actions)
            FallbackFonts()
            LaunchedEffect(Unit) {
                // The first frame draws before the strings and fonts are in: the splash stays a frame longer.
                withFrameNanos {}; withFrameNanos {}
                mark("hopp:first-frame")
                hideSplash()
            }
        }
    }
}
