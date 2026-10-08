package space.sovereigncast.hopp.di

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import space.sovereigncast.hopp.data.Clock
import space.sovereigncast.hopp.data.DownloadFiles
import space.sovereigncast.hopp.data.DownloadRepository
import space.sovereigncast.hopp.data.DownloadScheduler
import space.sovereigncast.hopp.data.QueueRepository
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.data.EpisodeRepository
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.directory.AppleClient
import space.sovereigncast.hopp.directory.DirectoryRepository
import space.sovereigncast.hopp.directory.FyydClient
import space.sovereigncast.hopp.directory.PodcastIndexClient
import space.sovereigncast.hopp.net.FeedFetcher
import space.sovereigncast.hopp.net.createHttpClient
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.platform.PlaybackEngine
import space.sovereigncast.hopp.platform.SecretStore
import space.sovereigncast.hopp.player.PlayerController
import space.sovereigncast.hopp.value.LnAddressClient
import space.sovereigncast.hopp.value.ValueService
import space.sovereigncast.hopp.value.WalletRepository
import space.sovereigncast.hopp.ui.library.DiscoverViewModel
import space.sovereigncast.hopp.ui.library.DownloadsViewModel
import space.sovereigncast.hopp.ui.library.EpisodeActionsViewModel
import space.sovereigncast.hopp.ui.library.QueueViewModel
import space.sovereigncast.hopp.ui.library.SettingsViewModel
import space.sovereigncast.hopp.ui.library.ShowPreviewViewModel
import space.sovereigncast.hopp.ui.library.InboxViewModel
import space.sovereigncast.hopp.ui.library.LibraryViewModel
import space.sovereigncast.hopp.ui.library.ShowViewModel

/** What every platform has to provide (ADR 0013: the platform/ interfaces). */
interface Platform {
    val app: AppInfo
    fun sqlDriver(): SqlDriver
    fun playbackEngine(): PlaybackEngine
    /** A directory outside the OS backup (ADR 0015). */
    fun downloadDir(): String
    fun downloadScheduler(): DownloadScheduler
    fun secretStore(): SecretStore
}

@OptIn(kotlin.time.ExperimentalTime::class)
fun sharedModule(platform: Platform) = module {
    single { platform.app }
    single { HoppDatabase(platform.sqlDriver()) }
    single { createHttpClient(get()) }
    single<Clock> { Clock { kotlin.time.Clock.System.now().epochSeconds } }
    single { FeedFetcher(get()) }
    single { DownloadFiles(platform.downloadDir()) }
    single { platform.downloadScheduler() }
    single { SettingsRepository(get()) }
    single { QueueRepository(get()) }
    single { DownloadRepository(get(), get(), get(), get(), get(), get()) }
    single { PodcastRepository(get(), get(), get(), get(), get()) }
    single { EpisodeRepository(get(), get(), get()) }
    single { platform.playbackEngine() }
    single { platform.secretStore() }
    single { WalletRepository(get(), get(), get()) }
    single { LnAddressClient(get()) }
    single { ValueService(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { PlayerController(get(), get(), get(), get(), get(), CoroutineScope(SupervisorJob() + Dispatchers.Main), get<ValueService>()) }
    viewModel { InboxViewModel(get(), get()) }
    viewModel { LibraryViewModel(get(), get(), get(), get()) }
    viewModel { params -> ShowViewModel(params.get(), get(), get()) }
    single { PodcastIndexClient(get(), get()) }
    single { FyydClient(get()) }
    single { AppleClient(get()) }
    single { DirectoryRepository(get(), get(), get(), get(), get()) }
    viewModel { DiscoverViewModel(get(), get()) }
    viewModel { params -> ShowPreviewViewModel(params.get(), get(), get()) }
    viewModel { QueueViewModel(get()) }
    viewModel { DownloadsViewModel(get()) }
    viewModel { EpisodeActionsViewModel(get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get()) }
}
