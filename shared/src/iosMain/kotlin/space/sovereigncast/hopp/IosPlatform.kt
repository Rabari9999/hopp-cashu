package space.sovereigncast.hopp

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import platform.Foundation.NSBundle
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.di.Platform
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.platform.PlaybackEngine
import space.sovereigncast.hopp.playback.IosPlaybackEngine

/** Hopp's identity on this device (ADR 0015). */
fun iosAppInfo() = AppInfo(
    version = NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String ?: "0.0.0",
    platform = "iOS",
    deviceClass = if (UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad) "Tablet" else "Phone",
)

class IosPlatform : Platform {
    override val app: AppInfo = iosAppInfo()
    override fun sqlDriver(): SqlDriver = NativeSqliteDriver(HoppDatabase.Schema, "hopp.db")
    override fun playbackEngine(): PlaybackEngine = IosPlaybackEngine(app.userAgent)
}
