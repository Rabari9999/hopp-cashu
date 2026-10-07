package space.sovereigncast.hopp

import androidx.compose.ui.window.ComposeUIViewController
import space.sovereigncast.hopp.ui.App

// The AVPlayer engine arrives with the iOS spike; until then the placeholder.
@Suppress("FunctionName", "unused") // called from Swift
fun MainViewController() = ComposeUIViewController { App(null) }
