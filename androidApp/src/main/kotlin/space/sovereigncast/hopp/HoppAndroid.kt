package space.sovereigncast.hopp

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.di.Platform
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.platform.PlaybackEngine
import space.sovereigncast.hopp.playback.AndroidPlaybackEngine

/** Hopp's identity on this device (ADR 0015). Tablet = smallest width ≥ 600 dp. */
fun appInfo(context: Context) = AppInfo(
    version = BuildConfig.VERSION_NAME,
    platform = "Android",
    deviceClass = if (context.resources.configuration.smallestScreenWidthDp >= 600) "Tablet" else "Phone",
)

class AndroidPlatform(private val context: Context) : Platform {
    override val app: AppInfo = appInfo(context)
    override fun sqlDriver(): SqlDriver = AndroidSqliteDriver(HoppDatabase.Schema, context, "hopp.db")
    override fun playbackEngine(): PlaybackEngine = AndroidPlaybackEngine(context)
}
