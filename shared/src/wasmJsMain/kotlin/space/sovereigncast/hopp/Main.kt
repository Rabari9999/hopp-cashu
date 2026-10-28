package space.sovereigncast.hopp

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import space.sovereigncast.hopp.di.sharedModule
import space.sovereigncast.hopp.ui.App

/** Hopp in the browser (ADR 0022, stage W2): the database first, then the app as on the phone. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    MainScope().launch {
        val driver = openWebDatabase()
        startKoin { modules(sharedModule(WebPlatform(driver))) }
        ComposeViewport(document.body!!) { App() }
    }
}
