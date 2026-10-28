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

/** Hopp in the browser (ADR 0022, stage W2): the database first, then the app as on the phone. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    MainScope().launch {
        if (!holdTabLock().await<JsBoolean>().toBoolean()) return@launch showOtherTab()
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
