package space.sovereigncast.hopp

import androidx.compose.ui.window.ComposeUIViewController
import org.koin.core.context.startKoin
import space.sovereigncast.hopp.di.sharedModule
import space.sovereigncast.hopp.ui.App

private val koin by lazy { startKoin { modules(sharedModule(IosPlatform())) } }

// Background refresh (BGAppRefreshTask) comes with the iOS work after phase 2 (ADR 0018).
@Suppress("FunctionName", "unused") // called from Swift
fun MainViewController() = ComposeUIViewController { koin; App(platformActions) }

private val platformActions = IosPlatformActions()

/**
 * A file another app handed to Hopp (Swift's onOpenURL) — an OPML export: its
 * text, read there, goes to the subscriptions page, which asks before it
 * imports. Too large or not OPML, the page says there are no shows in it.
 */
@Suppress("unused") // called from Swift
fun openIncomingFile(text: String?) {
    space.sovereigncast.hopp.ui.IncomingLinks.opml.value =
        text?.takeIf { it.length <= space.sovereigncast.hopp.net.Limits.OPML && space.sovereigncast.hopp.ui.IncomingLinks.isOpml(it) }.orEmpty()
}
