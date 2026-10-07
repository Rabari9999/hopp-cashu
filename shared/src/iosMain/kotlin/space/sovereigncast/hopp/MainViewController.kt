package space.sovereigncast.hopp

import androidx.compose.ui.window.ComposeUIViewController
import space.sovereigncast.hopp.playback.IosPlaybackEngine
import space.sovereigncast.hopp.ui.App
import space.sovereigncast.hopp.ui.AppDependencies

private val app by lazy { iosAppInfo() }
private val engine by lazy { IosPlaybackEngine(app.userAgent) }
private val deps by lazy { AppDependencies(app, engine, UserDefaultsPositionStore()) }

@Suppress("FunctionName", "unused") // called from Swift
fun MainViewController() = ComposeUIViewController { App(deps) }
