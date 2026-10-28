package space.sovereigncast.hopp

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import space.sovereigncast.hopp.di.sharedModule
import space.sovereigncast.hopp.platform.MemorySecretStore
import space.sovereigncast.hopp.platform.SecretStore
import space.sovereigncast.hopp.platform.WebSecretStore
import space.sovereigncast.hopp.ui.App

/** Hopp in the browser (ADR 0022, stage W2): the database first, then the app as on the phone. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    MainScope().launch {
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
