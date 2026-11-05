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

private fun browserLanguage(): String = js("navigator.language || ''")

// In the browser's language, from Hopp's list of them (Languages) — the app's strings are not loaded yet.
private fun showOtherTab() {
    val (title, line) = space.sovereigncast.hopp.ui.components.Languages.of(browserLanguage()).otherTab
    showOtherTab(title, line)
}

private fun showOtherTab(title: String, line: String): Unit = js("""{
  const box = document.createElement('div');
  box.style.cssText = 'font: 16px/1.5 system-ui, sans-serif; max-width: 28em; margin: 20vh auto; padding: 0 24px; text-align: center; color: #1a1a1a';
  const p1 = document.createElement('p'); const b = document.createElement('b'); b.textContent = title; p1.appendChild(b);
  const p2 = document.createElement('p'); p2.textContent = line;
  box.appendChild(p1); box.appendChild(p2);
  document.body.replaceChildren(box);
}""")

// Signing out in the browser takes everything Hopp kept there (ADR 0022, 2). The database's file
// is held by its worker while the app runs, so it goes on the next start, before the worker opens.
private fun markWipe(): Unit = js("{ try { localStorage.setItem('hopp-wipe', '1'); } catch (e) {} location.reload(); }")
private fun reload(): Unit = js("location.reload()")

// The worker of the tab just reloaded may hold the files for a moment (sqlite.worker.js asks again for the
// same reason): asked again here too, and the mark kept until they are gone (review 2026-10-31: one try,
// the mark cleared either way, and a library signed out of could stay). "held": still there after two
// seconds — this start keeps nothing, the next one tries again.
private fun wipeIfMarked(): Promise<JsString> = js("""(async () => {
  let marked = false;
  try { marked = localStorage.getItem('hopp-wipe') === '1'; } catch (e) {}
  if (!marked) return 'none';
  let root = null;
  try { root = await navigator.storage.getDirectory(); } catch (e) {}
  let held = !!root;
  for (let attempt = 0; attempt < 20 && held; attempt++) {
    if (attempt > 0) await new Promise(r => setTimeout(r, 100));
    try {
      for await (const [name] of root.entries()) await root.removeEntry(name, { recursive: true });
      held = false;
      for await (const _ of root.entries()) { held = true; break; }
    } catch (e) {}
  }
  await new Promise(ok => { const r = indexedDB.deleteDatabase('hopp-secrets'); r.onsuccess = r.onerror = r.onblocked = () => ok(); });
  if (held) return 'held';
  try { localStorage.removeItem('hopp-wipe'); } catch (e) {}
  return 'wiped';
})()""")

// Where the start's time goes, for the browser's developer tools (Performance, User Timing).
private fun mark(name: String): Unit = js("{ try { performance.mark(name); } catch (e) {} }")
private fun hideSplash(): Unit = js("""{ const s = document.getElementById('hopp-splash'); if (s) { s.style.opacity = '0'; setTimeout(() => s.remove(), 250); } }""")

// Signing out in a tab that keeps nothing: a reload starts it afresh. What the browser held when
// "This is not my device" was chosen went into that tab's memory then, and its files were deleted
// (WebTabOnly) — so nothing of it is left to wipe here.
internal fun wipeOnNextStart() = if (tabOnlyMarked()) reload() else markWipe()

/** Hopp in the browser (ADR 0022, stage W2): the database first, then the app as on the phone. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    mark("hopp:main")
    MainScope().launch {
        if (!holdTabLock().await<JsBoolean>().toBoolean()) return@launch showOtherTab()
        mark("hopp:tab-lock")
        // Only the tab that holds Hopp keeps its build (ADR 0022, 14).
        WebUpdates.start()
        val wipe = wipeIfMarked().await<JsString>().toString()
        mark("hopp:wipe-checked")
        // "This is not my device", chosen earlier in this tab: nothing opened that is stored.
        val tabOnly = tabOnlyMarked()
        // Signed out, and the old database still held: not opened — an empty one in memory until the next start.
        val driver = openWebDatabase(tabOnly || wipe == "held")
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
