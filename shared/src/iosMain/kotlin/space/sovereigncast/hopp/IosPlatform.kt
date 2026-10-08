package space.sovereigncast.hopp

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform
import platform.Foundation.NSBundle
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import space.sovereigncast.hopp.data.DownloadRepository
import space.sovereigncast.hopp.data.DownloadScheduler
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

    // Caches is outside the iCloud backup; iOS may empty it when space runs
    // out, and DownloadRepository.cleanup() then forgets the missing files.
    override fun downloadDir(): String {
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
        return "$caches/episodes"
    }

    // Until the background URLSession arrives with the iOS work after phase 2
    // (ADR 0018), downloads run while the app is open, and only requested ones:
    // without a network check, automatic ones would use mobile data.
    override fun downloadScheduler() = DownloadScheduler { _ ->
        MainScope().launch { KoinPlatform.getKoin().get<DownloadRepository>().runPending(includeAuto = false) }
    }
}
