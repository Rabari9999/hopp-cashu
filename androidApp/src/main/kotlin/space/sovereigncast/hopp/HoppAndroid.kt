package space.sovereigncast.hopp

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import space.sovereigncast.hopp.data.DownloadScheduler
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.download.WorkDownloadScheduler
import space.sovereigncast.hopp.di.Platform
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.platform.AndroidBoostNotifier
import space.sovereigncast.hopp.platform.AndroidNetworkPrivacy
import space.sovereigncast.hopp.value.BoostNotifier
import space.sovereigncast.hopp.platform.KeystoreSecretStore
import space.sovereigncast.hopp.platform.NetworkPrivacySource
import space.sovereigncast.hopp.platform.PlaybackEngine
import space.sovereigncast.hopp.platform.SecretStore
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
    // noBackupFilesDir: app-private, never in Auto Backup (ADR 0015).
    override fun downloadDir(): String = java.io.File(context.noBackupFilesDir, "episodes").absolutePath
    override fun downloadScheduler(): DownloadScheduler = WorkDownloadScheduler(context)
    override fun secretStore(): SecretStore = KeystoreSecretStore(context)
    // Outside Auto Backup too: a report is the listener's to share (ADR 0015).
    override fun crashDir(): String = java.io.File(context.noBackupFilesDir, "crash").absolutePath
    override fun networkPrivacy(): NetworkPrivacySource = AndroidNetworkPrivacy(context)
    override fun boostNotifier(): BoostNotifier = AndroidBoostNotifier(context)
    override fun externalSigner() = space.sovereigncast.hopp.platform.AndroidExternalSigner(context)
}
