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
import space.sovereigncast.hopp.identity.IdentityRepository
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
    /** A signer app on the device (NIP-55, Android); null where there is none. */
    fun externalSigner(): space.sovereigncast.hopp.nostr.ExternalSigner? = null
    /** Where a boost's result goes when the listener wants to hear of it. */
    fun boostNotifier(): space.sovereigncast.hopp.value.BoostNotifier = space.sovereigncast.hopp.value.BoostNotifier.None
    /** Whether episodes are downloaded here — not in the browser (ADR 0022, 1). */
    val downloads: Boolean get() = true
    /** After signing out: in the browser everything Hopp kept goes too — the library, the secrets (ADR 0022, 2). */
    suspend fun signedOut() = Unit
    /** Whether a Nostr key may be made or imported here — not in the browser (ADR 0022, 2). */
    val keysInApp: Boolean get() = true
    /** Whether a wallet may be connected over NWC and its connection kept — not in the browser until
     *  it has the protection of ADR 0022, 4 (stage W5; maintainer 2026-10-30). */
    val wallet: Boolean get() = true
    /** The browser's own wallet (WebLN, ADR 0022, 4, stage W3); null in the apps. */
    val browserWallet: space.sovereigncast.hopp.value.BrowserWallet? get() = null
    /** Whether the system looks for new episodes in the background (Android's WorkManager); where not,
     *  Hopp does while it is open ([space.sovereigncast.hopp.data.FeedRefresh.whileOpen]). */
    val backgroundRefresh: Boolean get() = true
    /** The one HTTP client; the web's sends what it fetches through the feed proxy (ADR 0022, 6). */
    fun httpClient(): io.ktor.client.HttpClient = createHttpClient(app)
}

@OptIn(kotlin.time.ExperimentalTime::class)
fun sharedModule(platform: Platform) = module {
    single { platform.app }
    single { HoppDatabase(platform.sqlDriver()) }
    single { platform.httpClient() }
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
    single { space.sovereigncast.hopp.identity.IdentityRepository(get(), get(), get(), platform.externalSigner(), platform.keysInApp, platform::signedOut) }
    single { space.sovereigncast.hopp.sync.SyncStore(get()) { kotlin.time.Clock.System.now().toEpochMilliseconds() } }
    single { space.sovereigncast.hopp.sync.SyncRelays(get(), get<Clock>()::now) }
    single { space.sovereigncast.hopp.sync.SyncKeys(get(), get(), get()) { kotlin.time.Clock.System.now().toEpochMilliseconds() } }
    single { space.sovereigncast.hopp.sync.SyncEngine(get(), get(), get(), get()) { kotlin.time.Clock.System.now().toEpochMilliseconds() } }
    single { space.sovereigncast.hopp.sync.SyncJournal(get()) }
    single {
        val koin = getKoin()
        space.sovereigncast.hopp.sync.SyncManager(get(), get(), get(), get(), CoroutineScope(SupervisorJob() + Dispatchers.Default), {
            val player = koin.get<PlayerController>()
            listOf(
                // Settings first: shows that follow download only as they say (device feedback 2026-10-24).
                space.sovereigncast.hopp.sync.SettingsDocument(koin.get()) { koin.get<DownloadRepository>().dropAutomatic() },
                space.sovereigncast.hopp.sync.SubscriptionsDocument(koin.get(), koin.get()),
                space.sovereigncast.hopp.sync.ProgressDocument(koin.get(), koin.get(), koin.get<Clock>()::now, player::isPlaying) { id, pos, played ->
                    kotlinx.coroutines.withContext(Dispatchers.Main) { player.adoptProgress(id, pos, played) }
                },
                space.sovereigncast.hopp.sync.QueueDocument(koin.get(), koin.get()),
                space.sovereigncast.hopp.sync.StatsDocument(koin.get(), koin.get()) { kotlin.time.Clock.System.now().toEpochMilliseconds() },
            )
        }, { kotlin.time.Clock.System.now().toEpochMilliseconds() }, afterRound = { koin.get<PodcastRepository>().releaseHeldDownloads() })
    }
    single { DownloadFiles(platform.downloadDir()) }
    single { platform.downloadScheduler() }
    single { space.sovereigncast.hopp.platform.CrashReports(platform.crashDir()) }
    single { SettingsRepository(get(), get()) }
    single { QueueRepository(get(), get()) }
    single { DownloadRepository(get(), get(), get(), get(), get(), get(), enabled = platform.downloads) }
    single { PodcastRepository(get(), get(), get(), get(), get(), get()) { platform.downloads && get<SettingsRepository>().current().autoDownloads } }
    single { EpisodeRepository(get(), get(), get(), get()) }
    single {
        val koin = getKoin()
        space.sovereigncast.hopp.data.FeedRefresh(get(), get(), CoroutineScope(SupervisorJob() + Dispatchers.Main), get<Clock>()::now,
            whileOpen = !platform.backgroundRefresh,
            before = { koin.get<space.sovereigncast.hopp.sync.SyncManager>().syncNow() },
            after = {
                koin.get<SovereigncastRepository>().refresh()
                // Sync ran before the feeds: what waited for an episode they just brought, now (maintainer 2026-11-01).
                val sync = koin.get<space.sovereigncast.hopp.sync.SyncManager>()
                if (sync.status.value.pending > 0) sync.syncNow()
            })
    }
    single { space.sovereigncast.hopp.data.PeopleRepository(get()) }
    single { platform.playbackEngine() }
    single { platform.secretStore() }
    single { platform.networkPrivacy() }
    single { WalletRepository(get(), get(), get(), enabled = platform.wallet, browser = platform.browserWallet) }
    single { LnAddressClient(get()) }
    single { PaymentMetadataSource(get(), get()) }
    single { ValueService(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { SovereigncastRepository(get(), get(), get(), get(), get(), get(), get()) }
    single { space.sovereigncast.hopp.sovereigncast.ScDirectory(get(), get()) }
    single<space.sovereigncast.hopp.comments.CommentStore> { space.sovereigncast.hopp.comments.DbCommentStore(get()) }
    single { space.sovereigncast.hopp.nostr.Nip05(get()) }
    single {
        space.sovereigncast.hopp.comments.CommentReader(get(), get<Clock>()::now, get(),
            { get<space.sovereigncast.hopp.identity.IdentityRepository>().identity.value?.pubkey }, get<space.sovereigncast.hopp.nostr.Nip05>()::siteKey,
            { get<space.sovereigncast.hopp.identity.IdentityRepository>().readRelays() })
    }
    single { space.sovereigncast.hopp.comments.CommentWriter(get(), get(), get(), get<Clock>()::now) { get<space.sovereigncast.hopp.data.ListeningRepository>().counted(comments = 1) } }
    single { space.sovereigncast.hopp.data.ListeningRepository(get(), get(), get(), get()) }
    single { val identity = get<IdentityRepository>(); space.sovereigncast.hopp.comments.CommentZapper(get(), identity::signer, get(), get(), get<Clock>()::now) }
    single { space.sovereigncast.hopp.comments.CommentsRepository(get(), get(), get(), get()) }
    single {
        PlayerController(get(), get(), get(), get(), get(), CoroutineScope(SupervisorJob() + Dispatchers.Main), if (platform.wallet || platform.browserWallet != null) get<ValueService>() else null, listening = get())
            .also { player -> get<DownloadRepository>().playing = { player.current.value?.item?.id } }
    }
    viewModel { InboxViewModel(get(), get(), get()) }
    viewModel { space.sovereigncast.hopp.ui.library.StatsViewModel(get()) }
    viewModel { HomeViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { LibraryViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { params -> ShowViewModel(params.get(), get(), get(), get()) }
    viewModel { params -> CommunityViewModel(params.get(), get(), get(), get(), get()) }
    single { PodcastIndexClient(get(), get()) }
    single { FyydClient(get()) }
    single { AppleClient(get()) }
    single { DirectoryRepository(get(), get(), get(), get(), get()) }
    viewModel { DiscoverViewModel(get(), get(), get(), get(), get()) }
    viewModel { params -> space.sovereigncast.hopp.ui.library.PersonViewModel(params.get(), get(), get(), get()) }
    viewModel { params -> ShowPreviewViewModel(params.get(), get(), get(), get()) }
    viewModel { QueueViewModel(get()) }
    viewModel { DownloadsViewModel(get()) }
    viewModel { EpisodeActionsViewModel(get(), get(), get()) }
    viewModel { params -> EpisodeViewModel(params.get(), get(), get()) }
    viewModel { params -> space.sovereigncast.hopp.ui.comments.CommentsViewModel(params.get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { space.sovereigncast.hopp.ui.library.FavoritesViewModel(get()) }
    viewModel { SettingsViewModel(get(), get(), get(), get()) }
    viewModel { space.sovereigncast.hopp.ui.library.PrivacyViewModel(get(), get(), get()) }
    viewModel { WalletViewModel(get(), get(), get()) }
    viewModel { space.sovereigncast.hopp.ui.identity.NostrIdentityViewModel(get(), get(), get()) }
    viewModel { HistoryViewModel(get()) }
    single { space.sovereigncast.hopp.value.BoostReports(get(), platform.boostNotifier(), CoroutineScope(SupervisorJob() + Dispatchers.Main)) }
    viewModel { ValueBarViewModel(get(), get(), get(), get(), get(), get(), get()) }
}
