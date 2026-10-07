package space.sovereigncast.hopp

import platform.Foundation.NSBundle
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.platform.PositionStore

/** Hopp's identity on this device (ADR 0015). */
fun iosAppInfo() = AppInfo(
    version = NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String ?: "0.0.0",
    platform = "iOS",
    deviceClass = if (UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad) "Tablet" else "Phone",
)

/** Spike stand-in for the position column of the episode table (ADR 0013). */
class UserDefaultsPositionStore : PositionStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    override fun get(episodeId: String): Long = defaults.integerForKey("position.$episodeId")
    override fun put(episodeId: String, positionMs: Long) = defaults.setInteger(positionMs, forKey = "position.$episodeId")
}
