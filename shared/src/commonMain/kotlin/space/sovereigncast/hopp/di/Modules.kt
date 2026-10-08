package space.sovereigncast.hopp.di

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import space.sovereigncast.hopp.data.Clock
import space.sovereigncast.hopp.data.EpisodeRepository
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.net.FeedFetcher
import space.sovereigncast.hopp.net.createHttpClient
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.platform.PlaybackEngine
import space.sovereigncast.hopp.player.PlayerController
import space.sovereigncast.hopp.ui.library.DiscoverViewModel
import space.sovereigncast.hopp.ui.library.InboxViewModel
import space.sovereigncast.hopp.ui.library.LibraryViewModel
import space.sovereigncast.hopp.ui.library.ShowViewModel

/** What every platform has to provide (ADR 0013: the platform/ interfaces). */
interface Platform {
    val app: AppInfo
    fun sqlDriver(): SqlDriver
    fun playbackEngine(): PlaybackEngine
}

@OptIn(kotlin.time.ExperimentalTime::class)
fun sharedModule(platform: Platform) = module {
    single { platform.app }
    single { HoppDatabase(platform.sqlDriver()) }
    single { createHttpClient(get()) }
    single<Clock> { Clock { kotlin.time.Clock.System.now().epochSeconds } }
    single { FeedFetcher(get()) }
    single { PodcastRepository(get(), get(), get()) }
    single { EpisodeRepository(get(), get(), get()) }
    single { platform.playbackEngine() }
    single { PlayerController(get(), get(), get(), CoroutineScope(SupervisorJob() + Dispatchers.Main)) }
    viewModel { InboxViewModel(get(), get()) }
    viewModel { LibraryViewModel(get(), get()) }
    viewModel { params -> ShowViewModel(params.get(), get(), get()) }
    viewModel { DiscoverViewModel(get()) }
}
