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
import space.sovereigncast.hopp.sovereigncast.ListenerApi
import space.sovereigncast.hopp.sovereigncast.PaymentMetadataSource
import space.sovereigncast.hopp.sovereigncast.SovereigncastRepository
import space.sovereigncast.hopp.value.LnAddressClient
import space.sovereigncast.hopp.value.ValueService
import space.sovereigncast.hopp.value.WalletRepository
import space.sovereigncast.hopp.ui.library.DiscoverViewModel
import space.sovereigncast.hopp.ui.library.DownloadsViewModel
import space.sovereigncast.hopp.ui.library.EpisodeActionsViewModel
import space.sovereigncast.hopp.ui.library.EpisodeViewModel
import space.sovereigncast.hopp.ui.library.QueueViewModel
import space.sovereigncast.hopp.ui.library.SettingsViewModel
import space.sovereigncast.hopp.ui.library.ShowPreviewViewModel
import space.sovereigncast.hopp.ui.sovereigncast.CommunityViewModel
import space.sovereigncast.hopp.ui.value.HistoryViewModel
import space.sovereigncast.hopp.ui.value.ValueBarViewModel
import space.sovereigncast.hopp.ui.value.WalletViewModel
import space.sovereigncast.hopp.ui.library.HomeViewModel
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
    /** Where the crash handler leaves its report, outside the OS backup (ADR 0015); null = none. */
    fun crashDir(): String? = null
    /** VPN and private DNS, for the privacy page; unknown where it cannot tell. */
    fun networkPrivacy(): space.sovereigncast.hopp.platform.NetworkPrivacySource = space.sovereigncast.hopp.platform.NetworkPrivacySource.Unknown
    /** Where a boost's result goes when the listener wants to hear of it. */
    fun boostNotifier(): space.sovereigncast.hopp.value.BoostNotifier = space.sovereigncast.hopp.value.BoostNotifier.None
}

@OptIn(kotlin.time.ExperimentalTime::class)
fun sharedModule(platform: Platform) = module {
    single { platform.app }
    single { HoppDatabase(platform.sqlDriver()) }
    single { createHttpClient(get()) }
    single<Clock> { Clock { kotlin.time.Clock.System.now().epochSeconds } }
    single { FeedFetcher(get()) }
    single {
        val koin = getKoin()
        // Signed only with an identity and unless the listener chose to stay anonymous (ADR 0021, 7).
        ListenerApi(get()) { url, method, body ->
            val signer = koin.get<space.sovereigncast.hopp.identity.IdentityRepository>().signer() ?: return@ListenerApi null
            if (koin.get<SettingsRepository>().current().scAnonymous) return@ListenerApi null
            space.sovereigncast.hopp.nostr.Nip98.authorization(signer, url, method, body, koin.get<Clock>().now())
        }
    }
    single { space.sovereigncast.hopp.nostr.RelayPublisher(space.sovereigncast.hopp.nostr.KtorRelayConnector(get())) }
    single { space.sovereigncast.hopp.identity.IdentityRepository(get(), get(), get()) }
    single { DownloadFiles(platform.downloadDir()) }
    single { platform.downloadScheduler() }
    single { space.sovereigncast.hopp.platform.CrashReports(platform.crashDir()) }
    single { SettingsRepository(get()) }
    single { QueueRepository(get()) }
    single { DownloadRepository(get(), get(), get(), get(), get(), get()) }
    single { PodcastRepository(get(), get(), get(), get(), get()) }
    single { EpisodeRepository(get(), get(), get()) }
    single { platform.playbackEngine() }
    single { platform.secretStore() }
    single { platform.networkPrivacy() }
    single { WalletRepository(get(), get(), get()) }
    single { LnAddressClient(get()) }
    single { PaymentMetadataSource(get(), get()) }
    single { ValueService(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { SovereigncastRepository(get(), get(), get(), get(), get(), get(), get()) }
    single { space.sovereigncast.hopp.sovereigncast.ScDirectory(get(), get()) }
    single { PlayerController(get(), get(), get(), get(), get(), CoroutineScope(SupervisorJob() + Dispatchers.Main), get<ValueService>()) }
    viewModel { InboxViewModel(get(), get()) }
    viewModel { HomeViewModel(get(), get(), get(), get(), get()) }
    viewModel { LibraryViewModel(get(), get(), get(), get(), get()) }
    viewModel { params -> ShowViewModel(params.get(), get(), get()) }
    viewModel { params -> CommunityViewModel(params.get(), get(), get(), get(), get()) }
    single { PodcastIndexClient(get(), get()) }
    single { FyydClient(get()) }
    single { AppleClient(get()) }
    single { DirectoryRepository(get(), get(), get(), get(), get()) }
    viewModel { DiscoverViewModel(get(), get(), get(), get()) }
    viewModel { params -> ShowPreviewViewModel(params.get(), get(), get()) }
    viewModel { QueueViewModel(get()) }
    viewModel { DownloadsViewModel(get()) }
    viewModel { EpisodeActionsViewModel(get(), get(), get()) }
    viewModel { params -> EpisodeViewModel(params.get(), get(), get()) }
    viewModel { space.sovereigncast.hopp.ui.library.FavoritesViewModel(get()) }
    viewModel { SettingsViewModel(get(), get(), get(), get()) }
    viewModel { space.sovereigncast.hopp.ui.library.PrivacyViewModel(get(), get(), get()) }
    viewModel { WalletViewModel(get(), get(), get()) }
    viewModel { space.sovereigncast.hopp.ui.identity.NostrIdentityViewModel(get(), get()) }
    viewModel { HistoryViewModel(get()) }
    single { space.sovereigncast.hopp.value.BoostReports(get(), platform.boostNotifier(), CoroutineScope(SupervisorJob() + Dispatchers.Main)) }
    viewModel { ValueBarViewModel(get(), get(), get(), get()) }
}
