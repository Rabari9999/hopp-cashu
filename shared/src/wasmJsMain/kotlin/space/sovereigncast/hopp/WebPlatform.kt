package space.sovereigncast.hopp

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import space.sovereigncast.hopp.data.DownloadScheduler
import space.sovereigncast.hopp.di.Platform
import space.sovereigncast.hopp.net.createHttpClient
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.platform.MemorySecretStore
import space.sovereigncast.hopp.platform.PlaybackEngine
import space.sovereigncast.hopp.platform.SecretStore
import space.sovereigncast.hopp.playback.HtmlAudioPlaybackEngine

/** Hopp in the browser (ADR 0022). [driver]: the database, opened before ([openWebDatabase]). */
class WebPlatform(private val driver: SqlDriver, private val secrets: SecretStore = MemorySecretStore()) : Platform {
    override val app: AppInfo = AppInfo(version = WebVersion.NAME, platform = "Web", deviceClass = "Browser")
    override fun sqlDriver(): SqlDriver = driver
    override fun playbackEngine(): PlaybackEngine = HtmlAudioPlaybackEngine()

    // No downloads on the web in the first version (ADR 0022, 1).
    override fun downloadDir(): String = "/downloads"
    override fun downloadScheduler(): DownloadScheduler = DownloadScheduler.None

    override fun secretStore(): SecretStore = secrets

    override fun httpClient(): HttpClient = createHttpClient(app) { install(FeedProxy) { base = feedProxyBase() } }
}
