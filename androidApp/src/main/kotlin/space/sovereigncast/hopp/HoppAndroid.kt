package space.sovereigncast.hopp

import android.content.Context
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.platform.PositionStore

/** Hopp's identity on this device (ADR 0015). Tablet = smallest width ≥ 600 dp. */
fun appInfo(context: Context) = AppInfo(
    version = BuildConfig.VERSION_NAME,
    platform = "Android",
    deviceClass = if (context.resources.configuration.smallestScreenWidthDp >= 600) "Tablet" else "Phone",
)

/** Spike stand-in for the position column of the episode table (ADR 0013). */
class PrefsPositionStore(context: Context) : PositionStore {
    private val prefs = context.getSharedPreferences("positions", Context.MODE_PRIVATE)
    override fun get(episodeId: String) = prefs.getLong(episodeId, 0L)
    override fun put(episodeId: String, positionMs: Long) = prefs.edit().putLong(episodeId, positionMs).apply()
}
