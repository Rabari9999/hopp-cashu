package space.sovereigncast.hopp

import androidx.compose.ui.window.ComposeUIViewController
import org.koin.core.context.startKoin
import space.sovereigncast.hopp.di.sharedModule
import space.sovereigncast.hopp.ui.App

private val koin by lazy { startKoin { modules(sharedModule(IosPlatform())) } }

// OPML import/export and background refresh (BGAppRefreshTask) come with the
// iOS work after phase 2 (ADR 0018).
@Suppress("FunctionName", "unused") // called from Swift
fun MainViewController() = ComposeUIViewController { koin; App() }
