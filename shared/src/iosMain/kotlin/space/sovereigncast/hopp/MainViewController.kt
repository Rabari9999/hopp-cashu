package space.sovereigncast.hopp

import androidx.compose.ui.window.ComposeUIViewController
import space.sovereigncast.hopp.ui.App

@Suppress("FunctionName", "unused") // called from Swift
fun MainViewController() = ComposeUIViewController { App() }
