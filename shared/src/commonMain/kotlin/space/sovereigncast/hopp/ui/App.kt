package space.sovereigncast.hopp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry
import space.sovereigncast.hopp.ui.library.EpisodeNav
import space.sovereigncast.hopp.ui.library.EpisodeScreen
import space.sovereigncast.hopp.ui.library.LocalEpisodeNav
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Home
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
import space.sovereigncast.hopp.resources.nav_home
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
import space.sovereigncast.hopp.resources.privacy_title
import space.sovereigncast.hopp.ui.library.SettingsPage
import space.sovereigncast.hopp.ui.library.SettingsPageScreen
import space.sovereigncast.hopp.data.SettingsRepository
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import space.sovereigncast.hopp.resources.crash_title
import space.sovereigncast.hopp.resources.crash_ask
import space.sovereigncast.hopp.resources.crash_view
import space.sovereigncast.hopp.resources.crash_delete
import space.sovereigncast.hopp.platform.CrashReports
import space.sovereigncast.hopp.ui.library.CrashReportScreen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import space.sovereigncast.hopp.ui.library.PrivacyScreen
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
import space.sovereigncast.hopp.ui.library.EpisodeActionsViewModel
import space.sovereigncast.hopp.ui.library.LocalDownloadProgress
import space.sovereigncast.hopp.ui.library.LocalEpisodeActions
import org.koin.compose.viewmodel.koinViewModel
import space.sovereigncast.hopp.ui.library.QueueScreen
import space.sovereigncast.hopp.ui.library.HomeScreen
import space.sovereigncast.hopp.ui.library.InboxScreen
import space.sovereigncast.hopp.ui.library.LibraryScreen
import space.sovereigncast.hopp.ui.library.SettingsScreen
import space.sovereigncast.hopp.ui.library.ShowPreviewScreen
import space.sovereigncast.hopp.ui.library.ShowScreen
import space.sovereigncast.hopp.ui.player.MiniPlayer
import space.sovereigncast.hopp.ui.player.NowPlayingScreen
import space.sovereigncast.hopp.ui.theme.HoppTheme

@Serializable object HomeRoute
@Serializable object InboxRoute
@Serializable object LibraryRoute
@Serializable object DiscoverRoute
/** The settings and what hangs off them — closed when the listener leaves for a tab. */
private val SETTINGS_ROUTES = listOf(
    SettingsRoute::class, SettingsPageRoute::class, WalletRoute::class, HistoryRoute::class, PrivacyRoute::class, CrashRoute::class,
)

@Serializable object SettingsRoute
@Serializable data class SettingsPageRoute(val page: String)
@Serializable data class ShowRoute(val id: Long)
@Serializable data class EpisodeRoute(val id: Long)
/** What the directory already knows rides along, so the page shows it at once. */
@Serializable data class ShowPreviewRoute(val feedUrl: String, val title: String? = null, val imageUrl: String? = null, val author: String? = null)
@Serializable data class WalletRoute(val uri: String? = null)
@Serializable object HistoryRoute
@Serializable object PrivacyRoute
@Serializable object CrashRoute
@Serializable object QueueRoute
@Serializable object DownloadsRoute

private data class Tab(val route: Any, val label: StringResource, val icon: ImageVector)

private val tabs = listOf(
    Tab(HomeRoute, Res.string.nav_home, Icons.Rounded.Home),
    Tab(LibraryRoute, Res.string.nav_library, Icons.AutoMirrored.Rounded.List),
    Tab(DiscoverRoute, Res.string.nav_discover, Icons.Rounded.Search),
)

/**
 * Hopp's frame (ADR 0019): three tabs — Home, Library, Discover — with the
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
        // The settings belong to no tab (device feedback 2026-10-10): leaving
        // them for a tab closes them, so the tab they were opened from comes
        // back where it was, not in the settings.
        val settingsAt = backStack.indexOfFirst { e -> SETTINGS_ROUTES.any { e.destination.hasRoute(it) } }
        if (settingsAt > 0) nav.popBackStack(backStack[settingsAt - 1].destination.id, inclusive = false)
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
        destination?.hasRoute(SettingsPageRoute::class) == true ->
            entry?.toRoute<SettingsPageRoute>()?.page?.let { p -> SettingsPage.entries.firstOrNull { it.name == p } }?.let { stringResource(it.title) } ?: ""
        destination?.hasRoute(QueueRoute::class) == true -> stringResource(Res.string.queue_title)
        destination?.hasRoute(InboxRoute::class) == true -> stringResource(Res.string.nav_new)
        destination?.hasRoute(WalletRoute::class) == true -> stringResource(Res.string.wallet_title)
        destination?.hasRoute(HistoryRoute::class) == true -> stringResource(Res.string.wallet_history)
        destination?.hasRoute(PrivacyRoute::class) == true -> stringResource(Res.string.privacy_title)
        destination?.hasRoute(CrashRoute::class) == true -> stringResource(Res.string.crash_title)
        destination?.hasRoute(DownloadsRoute::class) == true -> stringResource(Res.string.downloads_title)
        else -> ""
    }
    val showRoute = { id: Long -> nav.navigate(ShowRoute(id)) }

    val episodeActions: EpisodeActionsViewModel = koinViewModel()
    // A row opens the episode's page; its round button plays without opening
    // the player (as in Pocket Casts).
    val episodeNav = remember(nav, player) {
        EpisodeNav(open = { nav.navigate(EpisodeRoute(it.id)) }, play = { player.play(it, expand = false) })
    }
    val value: ValueService = koinInject()
    val snackbar = remember { SnackbarHostState() }
    // Streaming paid after time offline: say so (ADR 0014).
    LaunchedEffect(Unit) {
        value.notices.collect { n -> snackbar.showSnackbar(getString(Res.string.stream_notice, formatSats(n.paidSats), n.episodes)) }
    }
    // The first start's tour (device feedback 2026-10-10), or again from "About".
    val settingsRepo: SettingsRepository = koinInject()
    val appSettings by settingsRepo.settings.collectAsState(null)
    var tour by rememberSaveable { mutableStateOf(false) }
    val connectedWallet by koinInject<space.sovereigncast.hopp.value.WalletRepository>().wallet.collectAsState()
    val tourScope = rememberCoroutineScope()
    // A crash last time: offer the report, once per start (ADR 0015).
    val crashes: CrashReports = koinInject()
    var crashReport by remember { mutableStateOf(crashes.pending()) }
    var crashAsked by rememberSaveable { mutableStateOf(false) }
    // A wallet's "connect" link opens the wallet page, filled in.
    val incomingWallet by IncomingLinks.walletUri.collectAsState()
    LaunchedEffect(incomingWallet) {
        incomingWallet?.let { uri -> IncomingLinks.walletUri.value = null; player.minimize(); nav.navigate(WalletRoute(uri)) }
    }

    HoppTheme {
        androidx.compose.runtime.CompositionLocalProvider(LocalPlatformActions provides platformActions, LocalEpisodeActions provides episodeActions, LocalEpisodeNav provides episodeNav) {
          ProvideDownloadProgress {
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
                                        // A cogwheel: Hopp has no account (device feedback 2026-10-10).
                                        Icon(Icons.Rounded.Settings, stringResource(Res.string.settings))
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
                        NavHost(
                            nav, startDestination = HomeRoute, modifier = Modifier.padding(padding),
                            // As in Pocket Casts: a level deeper slides in from the side,
                            // a tab fades through (Material motion, device feedback 2026-10-08).
                            enterTransition = { if (isTab(initialState) && isTab(targetState)) fadeThroughIn else sharedAxisIn(forward = true) },
                            exitTransition = { if (isTab(initialState) && isTab(targetState)) fadeThroughOut else sharedAxisOut(forward = true) },
                            popEnterTransition = { sharedAxisIn(forward = false) },
                            popExitTransition = { sharedAxisOut(forward = false) },
                        ) {
                            composable<HomeRoute> {
                                HomeScreen(onInbox = { nav.navigate(InboxRoute) }, onDiscover = { selectTab(tabs[2]) }, onShow = showRoute)
                            }
                            composable<InboxRoute> { InboxScreen(onDiscover = { selectTab(tabs[2]) }) }
                            composable<LibraryRoute> {
                                LibraryScreen(
                                    onShow = showRoute,
                                    onQueue = { nav.navigate(QueueRoute) }, onDownloads = { nav.navigate(DownloadsRoute) },
                                )
                            }
                            composable<QueueRoute> { QueueScreen() }
                            composable<DownloadsRoute> { DownloadsScreen() }
                            composable<EpisodeRoute> { e ->
                                EpisodeScreen(
                                    e.toRoute<EpisodeRoute>().id,
                                    onShow = { nav.navigate(ShowRoute(it)) },
                                    onGone = { if (nav.currentBackStackEntry?.id == e.id) nav.popBackStack() },
                                    onOpenWallet = { nav.navigate(WalletRoute()) },
                                )
                            }
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
                            composable<SettingsRoute> {
                                SettingsScreen(
                                    onWallet = { nav.navigate(WalletRoute()) }, onHistory = { nav.navigate(HistoryRoute) },
                                    onPage = { nav.navigate(SettingsPageRoute(it.name)) }, onPrivacy = { nav.navigate(PrivacyRoute) },
                                    onCrashReport = if (crashReport != null) ({ nav.navigate(CrashRoute) }) else null,
                                )
                            }
                            composable<SettingsPageRoute> { e ->
                                val page = SettingsPage.entries.firstOrNull { it.name == e.toRoute<SettingsPageRoute>().page } ?: SettingsPage.About
                                SettingsPageScreen(page, onTour = { tour = true })
                            }
                            composable<PrivacyRoute> { PrivacyScreen() }
                            composable<CrashRoute> {
                                CrashReportScreen(crashReport, onDelete = {
                                    crashes.clear(); crashReport = null
                                    if (nav.currentBackStackEntry?.destination?.hasRoute(CrashRoute::class) == true) nav.popBackStack()
                                })
                            }
                            composable<WalletRoute> { e ->
                                WalletScreen(incomingUri = e.toRoute<WalletRoute>().uri)
                            }
                            composable<HistoryRoute> { HistoryScreen() }
                            composable<ShowRoute> { e ->
                                val id = e.toRoute<ShowRoute>().id
                                // Only this page leaves, and only once — the show can vanish while
                                // the page waits in another tab's stack.
                                ShowScreen(id, onOpenWallet = { nav.navigate(WalletRoute()) }, onGone = { if (nav.currentBackStackEntry?.id == e.id) nav.popBackStack() })
                            }
                        }
                    }
                    if (crashReport != null && !crashAsked) AlertDialog(
                        onDismissRequest = { crashAsked = true },
                        title = { Text(stringResource(Res.string.crash_title)) },
                        text = { Text(stringResource(Res.string.crash_ask)) },
                        confirmButton = { TextButton(onClick = { crashAsked = true; player.minimize(); nav.navigate(CrashRoute) }) { Text(stringResource(Res.string.crash_view)) } },
                        dismissButton = { TextButton(onClick = { crashAsked = true; crashes.clear(); crashReport = null }) { Text(stringResource(Res.string.crash_delete)) } },
                    )
                    // Up with a decelerating curve, down with an accelerating one — the
                    // linear default felt like the device could not keep up.
                    AnimatedVisibility(
                        expanded,
                        enter = slideInVertically(tween(420, easing = EmphasizedDecelerate)) { it } + fadeIn(tween(180)),
                        exit = slideOutVertically(tween(280, easing = EmphasizedAccelerate)) { it } + fadeOut(tween(200, delayMillis = 80)),
                    ) {
                        NowPlayingScreen(player, onOpenWallet = { player.minimize(); nav.navigate(WalletRoute()) })
                    }
                    if (tour || appSettings?.onboardingDone == false) OnboardingScreen(hasWallet = connectedWallet != null) { connectWallet ->
                        tour = false
                        tourScope.launch { settingsRepo.update { it.copy(onboardingDone = true) } }
                        if (connectWallet) nav.navigate(WalletRoute())
                    }
                }
            }
          }
        }
    }
}

/** Download progress for every row — collected here, so that a download's
 *  progress recomposes the rows that show it, not the whole app frame. */
@Composable
private fun ProvideDownloadProgress(content: @Composable () -> Unit) {
    val downloads: DownloadRepository = koinInject()
    val progress by downloads.progress.collectAsState()
    androidx.compose.runtime.CompositionLocalProvider(LocalDownloadProgress provides progress, content = content)
}

private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private fun isTab(entry: NavBackStackEntry) = tabs.any { entry.destination.hasRoute(it.route::class) }

/** Material "shared axis X": the new page comes in a little from the side and fades in. */
private fun sharedAxisIn(forward: Boolean): EnterTransition =
    slideInHorizontally(tween(320, easing = Emphasized)) { w -> if (forward) w / 8 else -w / 8 } +
        fadeIn(tween(220, delayMillis = 60, easing = EmphasizedDecelerate))

private fun sharedAxisOut(forward: Boolean): ExitTransition =
    slideOutHorizontally(tween(320, easing = Emphasized)) { w -> if (forward) -w / 8 else w / 8 } +
        fadeOut(tween(100, easing = EmphasizedAccelerate))

/** Material "fade through" between tabs: out fast, in with a slight zoom. */
private val fadeThroughIn: EnterTransition = fadeIn(tween(220, delayMillis = 90, easing = EmphasizedDecelerate)) +
    scaleIn(tween(220, delayMillis = 90, easing = EmphasizedDecelerate), initialScale = 0.94f)
private val fadeThroughOut: ExitTransition = fadeOut(tween(90, easing = EmphasizedAccelerate))
