package space.sovereigncast.hopp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import space.sovereigncast.hopp.player.PlayerController
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.back
import space.sovereigncast.hopp.resources.nav_discover
import space.sovereigncast.hopp.resources.nav_library
import space.sovereigncast.hopp.resources.nav_new
import space.sovereigncast.hopp.resources.settings
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import org.jetbrains.compose.resources.getString
import space.sovereigncast.hopp.data.DownloadRepository
import space.sovereigncast.hopp.resources.stream_notice
import space.sovereigncast.hopp.resources.wallet_history
import space.sovereigncast.hopp.resources.wallet_title
import space.sovereigncast.hopp.ui.components.formatSats
import space.sovereigncast.hopp.ui.value.HistoryScreen
import space.sovereigncast.hopp.ui.value.WalletScreen
import space.sovereigncast.hopp.value.ValueService
import space.sovereigncast.hopp.directory.DirectoryShow
import space.sovereigncast.hopp.resources.downloads_title
import space.sovereigncast.hopp.resources.queue_title
import space.sovereigncast.hopp.ui.library.DiscoverScreen
import space.sovereigncast.hopp.ui.library.DownloadsScreen
import space.sovereigncast.hopp.ui.library.LocalDownloadProgress
import space.sovereigncast.hopp.ui.library.QueueScreen
import space.sovereigncast.hopp.ui.library.InboxScreen
import space.sovereigncast.hopp.ui.library.LibraryScreen
import space.sovereigncast.hopp.ui.library.SettingsScreen
import space.sovereigncast.hopp.ui.library.ShowPreviewScreen
import space.sovereigncast.hopp.ui.library.ShowScreen
import space.sovereigncast.hopp.ui.player.MiniPlayer
import space.sovereigncast.hopp.ui.player.NowPlayingScreen
import space.sovereigncast.hopp.ui.theme.HoppTheme

@Serializable object InboxRoute
@Serializable object LibraryRoute
@Serializable object DiscoverRoute
@Serializable object SettingsRoute
@Serializable data class ShowRoute(val id: Long)
/** What the directory already knows rides along, so the page shows it at once. */
@Serializable data class ShowPreviewRoute(val feedUrl: String, val title: String? = null, val imageUrl: String? = null, val author: String? = null)
@Serializable data class WalletRoute(val uri: String? = null)
@Serializable object HistoryRoute
@Serializable object QueueRoute
@Serializable object DownloadsRoute

private data class Tab(val route: Any, val label: StringResource, val icon: ImageVector)

private val tabs = listOf(
    Tab(InboxRoute, Res.string.nav_new, Icons.Rounded.Notifications),
    Tab(LibraryRoute, Res.string.nav_library, Icons.AutoMirrored.Rounded.List),
    Tab(DiscoverRoute, Res.string.nav_discover, Icons.Rounded.Search),
)

/**
 * Hopp's frame (ADR 0019): three tabs — New, Library, Discover — with the
 * mini player above the tab bar and now playing over everything. Settings
 * sit behind the profile button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(platformActions: PlatformActions = NoPlatformActions) {
    val http: HttpClient = koinInject()
    // Images through the shared client: Hopp's User-Agent on them too (ADR 0015).
    setSingletonImageLoaderFactory { context -> ImageLoader.Builder(context).components { add(KtorNetworkFetcherFactory(http)) }.build() }
    val player: PlayerController = koinInject()
    val expanded by player.expanded.collectAsState()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val backStack by nav.currentBackStack.collectAsState()
    val destination = entry?.destination
    val onTab = tabs.firstOrNull { tab -> destination?.hasRoute(tab.route::class) == true }
    // The tab whose stack is showing: a show opened from Discover belongs to
    // Discover, not to no tab at all.
    val currentTab = backStack.asReversed().firstNotNullOfOrNull { e -> tabs.firstOrNull { e.destination.hasRoute(it.route::class) } }
    // Each tab keeps its own stack (device feedback 2026-10-08: a show page
    // pushed onto New came back when New was tapped and looked like a New
    // tab full of episodes). Tapping the tab you are on goes back to its root.
    val selectTab = { tab: Tab ->
        if (tab == currentTab) nav.popBackStack(tab.route, inclusive = false)
        else nav.navigate(tab.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        Unit
    }
    val title = when {
        onTab != null -> stringResource(onTab.label)
        destination?.hasRoute(SettingsRoute::class) == true -> stringResource(Res.string.settings)
        destination?.hasRoute(QueueRoute::class) == true -> stringResource(Res.string.queue_title)
        destination?.hasRoute(WalletRoute::class) == true -> stringResource(Res.string.wallet_title)
        destination?.hasRoute(HistoryRoute::class) == true -> stringResource(Res.string.wallet_history)
        destination?.hasRoute(DownloadsRoute::class) == true -> stringResource(Res.string.downloads_title)
        else -> ""
    }
    val showRoute = { id: Long -> nav.navigate(ShowRoute(id)) }

    val downloads: DownloadRepository = koinInject()
    val progress by downloads.progress.collectAsState()
    val value: ValueService = koinInject()
    val snackbar = remember { SnackbarHostState() }
    // Streaming paid after time offline: say so (ADR 0014).
    LaunchedEffect(Unit) {
        value.notices.collect { n -> snackbar.showSnackbar(getString(Res.string.stream_notice, formatSats(n.paidSats), n.episodes)) }
    }
    // A wallet's "connect" link opens the wallet page, filled in.
    val incomingWallet by IncomingLinks.walletUri.collectAsState()
    LaunchedEffect(incomingWallet) {
        incomingWallet?.let { uri -> IncomingLinks.walletUri.value = null; player.minimize(); nav.navigate(WalletRoute(uri)) }
    }

    HoppTheme {
        androidx.compose.runtime.CompositionLocalProvider(LocalPlatformActions provides platformActions, LocalDownloadProgress provides progress) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize()) {
                    Scaffold(
                        containerColor = MaterialTheme.colorScheme.background,
                        snackbarHost = { SnackbarHost(snackbar) },
                        topBar = {
                            CenterAlignedTopAppBar(
                                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                                navigationIcon = {
                                    if (onTab == null) IconButton(onClick = { nav.popBackStack() }) {
                                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(Res.string.back))
                                    }
                                },
                                actions = {
                                    if (onTab != null) IconButton(onClick = { nav.navigate(SettingsRoute) }) {
                                        Icon(Icons.Rounded.AccountCircle, stringResource(Res.string.settings))
                                    }
                                },
                                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                            )
                        },
                        bottomBar = {
                            Column {
                                MiniPlayer(player)
                                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                                    tabs.forEach { tab ->
                                        NavigationBarItem(
                                            selected = currentTab == tab,
                                            onClick = { selectTab(tab) },
                                            icon = { Icon(tab.icon, null) },
                                            label = { Text(stringResource(tab.label)) },
                                        )
                                    }
                                }
                            }
                        },
                    ) { padding ->
                        NavHost(nav, startDestination = InboxRoute, modifier = Modifier.padding(padding)) {
                            composable<InboxRoute> { InboxScreen(onPlay = { player.play(it) }, onDiscover = { selectTab(tabs[2]) }) }
                            composable<LibraryRoute> {
                                LibraryScreen(
                                    onShow = showRoute, onPlay = { player.play(it) },
                                    onQueue = { nav.navigate(QueueRoute) }, onDownloads = { nav.navigate(DownloadsRoute) },
                                )
                            }
                            composable<QueueRoute> { QueueScreen(onPlay = { player.play(it) }) }
                            composable<DownloadsRoute> { DownloadsScreen(onPlay = { player.play(it) }) }
                            composable<DiscoverRoute> {
                                DiscoverScreen(onPreview = { url, show -> nav.navigate(ShowPreviewRoute(url, show?.title, show?.imageUrl, show?.author)) })
                            }
                            composable<ShowPreviewRoute> { e ->
                                // Subscribed: the show's own page takes the preview's place —
                                // unless the listener has moved on while it loaded.
                                val route = e.toRoute<ShowPreviewRoute>()
                                ShowPreviewScreen(
                                    route.feedUrl,
                                    known = route.title?.let { DirectoryShow(it, route.feedUrl, route.author, route.imageUrl) },
                                    onOpen = { id ->
                                        if (nav.currentBackStackEntry?.id == e.id) nav.navigate(ShowRoute(id)) { popUpTo<ShowPreviewRoute> { inclusive = true } }
                                    },
                                )
                            }
                            composable<SettingsRoute> { SettingsScreen(onWallet = { nav.navigate(WalletRoute()) }) }
                            composable<WalletRoute> { e ->
                                WalletScreen(onHistory = { nav.navigate(HistoryRoute) }, incomingUri = e.toRoute<WalletRoute>().uri)
                            }
                            composable<HistoryRoute> { HistoryScreen() }
                            composable<ShowRoute> { e ->
                                val id = e.toRoute<ShowRoute>().id
                                // Only this page leaves, and only once — the show can vanish while
                                // the page waits in another tab's stack.
                                ShowScreen(id, onPlay = { player.play(it) }, onGone = { if (nav.currentBackStackEntry?.id == e.id) nav.popBackStack() })
                            }
                        }
                    }
                    AnimatedVisibility(expanded, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                        NowPlayingScreen(player, onOpenWallet = { player.minimize(); nav.navigate(WalletRoute()) })
                    }
                }
            }
        }
    }
}
