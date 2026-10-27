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
import space.sovereigncast.hopp.sovereigncast.ShowAction
import space.sovereigncast.hopp.ui.library.EpisodeNav
import space.sovereigncast.hopp.ui.library.EpisodeScreen
import space.sovereigncast.hopp.ui.library.LocalEpisodeNav
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import space.sovereigncast.hopp.resources.stats_title
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.sync_restored
import space.sovereigncast.hopp.resources.back
import space.sovereigncast.hopp.resources.nav_discover
import space.sovereigncast.hopp.resources.nav_library
import space.sovereigncast.hopp.resources.nav_home
import space.sovereigncast.hopp.resources.nav_new
import space.sovereigncast.hopp.resources.settings
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getPluralString
import space.sovereigncast.hopp.resources.sync_restored_shows
import space.sovereigncast.hopp.data.DownloadRepository
import space.sovereigncast.hopp.resources.stream_notice
import space.sovereigncast.hopp.resources.wallet_history
import space.sovereigncast.hopp.resources.privacy_title
import space.sovereigncast.hopp.resources.favorites_title
import space.sovereigncast.hopp.ui.library.SettingsPage
import space.sovereigncast.hopp.ui.library.SettingsPageScreen
import space.sovereigncast.hopp.data.SettingsRepository
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
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
import space.sovereigncast.hopp.ui.library.SharedEpisode
import space.sovereigncast.hopp.resources.wallet_title
import space.sovereigncast.hopp.ui.components.formatSats
import space.sovereigncast.hopp.ui.components.LIST_WIDTH
import space.sovereigncast.hopp.ui.components.MEDIUM_WIDTH
import space.sovereigncast.hopp.ui.components.PLAYER_PANE_MIN
import space.sovereigncast.hopp.ui.components.PLAYER_PANE_SHARE
import space.sovereigncast.hopp.ui.components.PLAYER_PANE_WIDTH
import space.sovereigncast.hopp.ui.components.Readable
import space.sovereigncast.hopp.ui.components.keyShortcuts
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
    SettingsRoute::class, SettingsPageRoute::class, WalletRoute::class, HistoryRoute::class, PrivacyRoute::class, CrashRoute::class, StatsRoute::class,
)

@Serializable object SettingsRoute
/** [fromTour]: opened from the tour at that page — back returns to it (maintainer, 2026-10-21). */
@Serializable data class SettingsPageRoute(val page: String, val fromTour: Int? = null)
/** A show's page; [community] opens its Community tab (Home's "vote", device feedback 2026-10-12). */
/** [round] / [unlock] (an episode guid): open that action's sheet once the show knows it. */
@Serializable data class ShowRoute(val id: Long, val community: Boolean = false, val boosts: Boolean = false,
                                   val round: Boolean = false, val unlock: String? = null)
/** An episode's page; [startSeconds] from a shared link offers playing from there. */
@Serializable data class EpisodeRoute(val id: Long, val startSeconds: Long? = null)
/** What the directory already knows rides along, so the page shows it at once. */
@Serializable data class ShowPreviewRoute(
    val feedUrl: String, val title: String? = null, val imageUrl: String? = null, val author: String? = null,
    /** A shared link's episode and start: shown on top of the preview. */
    val episodeGuid: String? = null, val startSeconds: Long? = null,
)
/** A person across podcasts (0.3.1): what the place they were met in knew of them. */
@Serializable data class PersonRoute(val name: String, val img: String? = null, val href: String? = null, val role: String? = null, val show: String? = null)
@Serializable object WalletRoute
@Serializable object HistoryRoute
@Serializable object StatsRoute
@Serializable object PrivacyRoute
@Serializable object CrashRoute
@Serializable object QueueRoute
@Serializable object FavoritesRoute
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
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun App(platformActions: PlatformActions = NoPlatformActions) {
    val http: HttpClient = koinInject()
    // Images through the shared client: Hopp's User-Agent on them too (ADR 0015).
    setSingletonImageLoaderFactory { context -> ImageLoader.Builder(context).components { add(KtorNetworkFetcherFactory(http)); addAnimatedImages() }.build() }
    val player: PlayerController = koinInject()
    val directory: space.sovereigncast.hopp.directory.DirectoryRepository = koinInject()
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
        destination?.hasRoute(FavoritesRoute::class) == true -> stringResource(Res.string.favorites_title)
        destination?.hasRoute(InboxRoute::class) == true -> stringResource(Res.string.nav_new)
        destination?.hasRoute(WalletRoute::class) == true -> stringResource(Res.string.wallet_title)
        destination?.hasRoute(HistoryRoute::class) == true -> stringResource(Res.string.wallet_history)
        destination?.hasRoute(PersonRoute::class) == true -> entry?.toRoute<PersonRoute>()?.name.orEmpty()
        destination?.hasRoute(StatsRoute::class) == true -> stringResource(Res.string.stats_title)
        destination?.hasRoute(PrivacyRoute::class) == true -> stringResource(Res.string.privacy_title)
        destination?.hasRoute(CrashRoute::class) == true -> stringResource(Res.string.crash_title)
        destination?.hasRoute(DownloadsRoute::class) == true -> stringResource(Res.string.downloads_title)
        else -> ""
    }
    val showRoute = { id: Long -> nav.navigate(ShowRoute(id)) }
    val pageBack = remember { space.sovereigncast.hopp.ui.components.PageBack() }

    val episodeActions: EpisodeActionsViewModel = koinViewModel()
    // A row opens the episode's page; its round button plays without opening
    // the player (as in Pocket Casts).
    val episodeNav = remember(nav, player) {
        EpisodeNav(open = { nav.navigate(EpisodeRoute(it.id)) }, play = { player.play(it, expand = false) })
    }
    val value: ValueService = koinInject()
    val snackbar = remember { SnackbarHostState() }
    space.sovereigncast.hopp.ui.player.SkipNotices(player, snackbar, expanded = false)
    // Streaming paid after time offline: say so (ADR 0014).
    LaunchedEffect(Unit) {
        value.notices.collect { n -> snackbar.showSnackbar(getString(Res.string.stream_notice, formatSats(n.paidSats), n.episodes)) }
    }
    // The Sovereigncast list fetched ahead, so Discover marks and filters at
    // once instead of a moment after opening (device feedback 2026-10-11).
    val scDirectory: space.sovereigncast.hopp.sovereigncast.ScDirectory = koinInject()
    LaunchedEffect(Unit) { scDirectory.shows() }
    // The first start's tour (device feedback 2026-10-10), or again from "About".
    val settingsRepo: SettingsRepository = koinInject()
    // Sync (ADR 0021, 5): a round on start and back in the foreground, what is left goes on leaving.
    val sync: space.sovereigncast.hopp.sync.SyncManager = koinInject()
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_START) { sync.onForeground() }
    // The listening statistics noted before that round, all of it (they otherwise go every half hour).
    val listening: space.sovereigncast.hopp.data.ListeningRepository = koinInject()
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { sync.onBackground { listening.flush(force = true) } }
    val appSettings by settingsRepo.settings.collectAsState(null)
    var tour by rememberSaveable { mutableStateOf(false) }
    /** Where the tour was left for one of its pages, to come back to. */
    var tourPage by rememberSaveable { mutableStateOf(0) }
    val connectedWallet by koinInject<space.sovereigncast.hopp.value.WalletRepository>().wallet.collectAsState()
    val tourScope = rememberCoroutineScope()
    // A crash last time: offer the report, once per start (ADR 0015).
    val crashes: CrashReports = koinInject()
    var crashReport by remember { mutableStateOf(crashes.pending()) }
    var crashAsked by rememberSaveable { mutableStateOf(false) }
    // Links from outside (IncomingLinks): collected, not keyed on — taking a link
    // clears it, and a keyed effect would cancel itself mid-way. On a cold start
    // the link is there before the NavHost (inside the Scaffold's content,
    // composed later) has its graph: wait for its first entry.
    val podcastRepo: space.sovereigncast.hopp.data.PodcastRepository = koinInject()
    val episodeRepo: space.sovereigncast.hopp.data.EpisodeRepository = koinInject()
    val selectTabNow by androidx.compose.runtime.rememberUpdatedState(selectTab)
    LaunchedEffect(Unit) {
        nav.currentBackStackEntryFlow.first()
        launch {
            // A wallet's "connect" link opens the wallet page, filled in.
            IncomingLinks.walletUri.collect { uri ->
                if (uri == null) return@collect
                IncomingLinks.walletUri.value = null
                player.minimize(); IncomingLinks.handOverWallet(uri); nav.navigate(WalletRoute)
            }
        }
        launch {
            // An OPML file from another app: the subscriptions page, which asks first. Over the
            // tour too — whoever shares a file knows what they came for.
            IncomingLinks.opml.collect { text ->
                if (text == null) return@collect
                IncomingLinks.opml.value = null
                IncomingLinks.handOverOpml(text)
                if (tour || appSettings?.onboardingDone == false) {
                    tour = false
                    settingsRepo.update { it.copy(onboardingDone = true) }
                }
                withContext(Dispatchers.Main) {
                    player.minimize()
                    nav.navigate(SettingsPageRoute(SettingsPage.Subscriptions.name))
                }
            }
        }
        launch {
            // Sync turned on brought the listener's library: Home, where it now is, and a line
            // that says so — the Nostr page's status alone went unseen (maintainer, 2026-10-21).
            sync.restored.collect { r ->
                withContext(Dispatchers.Main) {
                    player.minimize()
                    selectTabNow(tabs[0])
                }
                snackbar.showSnackbar(
                    if (r.shows > 0) getPluralString(Res.plurals.sync_restored_shows, r.shows, r.shows) else getString(Res.string.sync_restored),
                    withDismissAction = true,
                )
            }
        }
        // "Open in Hopp" (ADR 0002 §4): the show's preview on Discover, or —
        // subscribed under this address — its page on Library; each through its
        // tab, as a tap would.
        IncomingLinks.show.collect { link ->
            if (link == null) return@collect
            IncomingLinks.show.value = null
            val key = space.sovereigncast.hopp.directory.feedKey(link.feed)
            val subscribed = podcastRepo.shows.first().firstOrNull { space.sovereigncast.hopp.directory.feedKey(it.feedUrl) == key }
            val episode = if (subscribed != null) link.episode?.let { episodeRepo.byGuid(subscribed.id, it) } else null
            // The database answers on its own thread; the NavController wants the main one.
            withContext(Dispatchers.Main) {
                player.minimize()
                if (subscribed != null) {
                    // The show's page, and on it the episode's, when the link names one.
                    selectTabNow(tabs[1]); nav.navigate(ShowRoute(subscribed.id))
                    episode?.let { nav.navigate(EpisodeRoute(it.id, link.startSeconds)) }
                } else {
                    selectTabNow(tabs[2]); nav.navigate(ShowPreviewRoute(link.feed, episodeGuid = link.episode, startSeconds = link.startSeconds))
                }
            }
        }
    }

    HoppTheme {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalPlatformActions provides platformActions, LocalEpisodeActions provides episodeActions, LocalEpisodeNav provides episodeNav,
            space.sovereigncast.hopp.ui.components.LocalPageBack provides pageBack,
            // From the comments, in the player or on an episode page (ADR 0021, stage 3).
            space.sovereigncast.hopp.ui.identity.LocalOpenStats provides { nav.navigate(StatsRoute) },
            // A person's page (0.3.1) — from an episode, or from the player, which makes way for it.
            space.sovereigncast.hopp.ui.components.LocalOpenPerson provides { p, show ->
                player.minimize(); nav.navigate(PersonRoute(p.name.trim(), p.img, p.href, p.role, show))
            },
            space.sovereigncast.hopp.ui.comments.LocalOpenIdentity provides { player.minimize(); nav.navigate(SettingsPageRoute(space.sovereigncast.hopp.ui.library.SettingsPage.Nostr.name)) },
            space.sovereigncast.hopp.ui.comments.LocalOpenWallet provides { player.minimize(); nav.navigate(WalletRoute) },
            // A show's category, opened in Discover (device feedback 2026-10-19).
            space.sovereigncast.hopp.ui.library.LocalOpenCategory provides { c -> directory.requested.value = c; selectTab(tabs[2]) },
        ) {
          ProvideDownloadProgress {
            // Space, ← →, Escape for a keyboard (ADR 0020, step 3) — only with an episode loaded.
            val shortcuts = remember(player) {
                space.sovereigncast.hopp.ui.components.KeyShortcuts(
                    playPause = { if (player.current.value != null) player.togglePlay() },
                    back = { if (player.current.value != null) player.skipBack() },
                    forward = { if (player.current.value != null) player.skipForward() },
                    close = { if (player.expanded.value) { player.minimize(); true } else false },
                )
            }
            Surface(Modifier.fillMaxSize().keyShortcuts(shortcuts), color = MaterialTheme.colorScheme.background) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                  // A rail at the side once the window is wider than a phone held
                  // upright (ADR 0020): a tablet, a phone on its side, a Mac window.
                  val rail = maxWidth >= MEDIUM_WIDTH
                  // Wide enough, now playing stands as a column on the right instead of
                  // covering everything (ADR 0020, step 2) — the library stays in reach.
                  val playerPane = maxWidth >= PLAYER_PANE_WIDTH
                  val paneWidth = maxOf(PLAYER_PANE_MIN, maxWidth * PLAYER_PANE_SHARE)
                  val paneOpen = playerPane && expanded
                  Row(Modifier.fillMaxSize()) {
                    if (rail) NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        tabs.forEach { tab ->
                            NavigationRailItem(
                                selected = currentTab == tab,
                                onClick = { selectTab(tab) },
                                icon = { Icon(tab.icon, null) },
                                label = { Text(stringResource(tab.label)) },
                            )
                        }
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                    Scaffold(
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.background,
                        snackbarHost = { SnackbarHost(snackbar) },
                        topBar = {
                            CenterAlignedTopAppBar(
                                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                                navigationIcon = {
                                    // The page's own step first (a picked episode), then the back stack.
                                    if (onTab == null) IconButton(onClick = { pageBack.step?.invoke() ?: nav.popBackStack() }) {
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
                                if (!paneOpen) MiniPlayer(player)
                                if (!rail) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
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
                            composable<HomeRoute> { Readable(LIST_WIDTH) {
                                HomeScreen(onInbox = { nav.navigate(InboxRoute) }, onDiscover = { selectTab(tabs[2]) }, onShow = showRoute, onFavorites = { nav.navigate(FavoritesRoute) },
                                    onCommunity = { nav.navigate(ShowRoute(it, community = true)) }, snackbar = snackbar,
                                    onAction = { a -> nav.navigate(ShowRoute(a.podcastId, round = a.kind == ShowAction.Kind.ReleaseBoosting,
                                        unlock = a.episodeGuid.takeIf { a.kind == ShowAction.Kind.Boost2Unlock })) },
                                    onLibrary = { selectTab(tabs[1]) },
                                    onCustomize = { nav.navigate(SettingsPageRoute(SettingsPage.Home.name)) },
                                    onSyncSettings = { nav.navigate(SettingsPageRoute(SettingsPage.Nostr.name)) })
                            } }
                            composable<InboxRoute> { Readable(LIST_WIDTH) { InboxScreen(onDiscover = { selectTab(tabs[2]) }) } }
                            composable<LibraryRoute> {
                                LibraryScreen(
                                    onShow = showRoute,
                                    onQueue = { nav.navigate(QueueRoute) }, onDownloads = { nav.navigate(DownloadsRoute) },
                                )
                            }
                            composable<QueueRoute> { Readable(LIST_WIDTH) { QueueScreen() } }
                            composable<FavoritesRoute> { Readable(LIST_WIDTH) { space.sovereigncast.hopp.ui.library.FavoritesScreen() } }
                            composable<DownloadsRoute> { Readable(LIST_WIDTH) { DownloadsScreen() } }
                            composable<EpisodeRoute> { e ->
                                val route = e.toRoute<EpisodeRoute>()
                                Readable { EpisodeScreen(
                                    route.id, startSeconds = route.startSeconds,
                                    onShow = { nav.navigate(ShowRoute(it)) },
                                    onGone = { if (nav.currentBackStackEntry?.id == e.id) nav.popBackStack() },
                                    onOpenWallet = { nav.navigate(WalletRoute) },
                                ) }
                            }
                            composable<DiscoverRoute> { Readable(LIST_WIDTH) {
                                DiscoverScreen(
                                    onPreview = { url, show -> nav.navigate(ShowPreviewRoute(url, show?.title, show?.imageUrl, show?.author)) },
                                    onPerson = { p -> nav.navigate(PersonRoute(p.name, p.img, p.href, p.role, p.show)) },
                                )
                            } }
                            composable<ShowPreviewRoute> { e ->
                                // Subscribed: the show's own page takes the preview's place —
                                // unless the listener has moved on while it loaded.
                                val route = e.toRoute<ShowPreviewRoute>()
                                Readable(LIST_WIDTH) { ShowPreviewScreen(
                                    route.feedUrl,
                                    known = route.title?.let { DirectoryShow(it, route.feedUrl, route.author, route.imageUrl) },
                                    onOpen = { id ->
                                        if (nav.currentBackStackEntry?.id == e.id) nav.navigate(ShowRoute(id)) { popUpTo<ShowPreviewRoute> { inclusive = true } }
                                    },
                                    shared = route.episodeGuid?.let { SharedEpisode(it, route.startSeconds) },
                                    // Subscribed from a shared episode: the show's page, and the episode's on top.
                                    onOpenShared = { id, guid, start ->
                                        tourScope.launch {
                                            val ep = episodeRepo.byGuid(id, guid)
                                            withContext(Dispatchers.Main) {
                                                if (nav.currentBackStackEntry?.id != e.id) return@withContext
                                                nav.navigate(ShowRoute(id)) { popUpTo<ShowPreviewRoute> { inclusive = true } }
                                                ep?.let { nav.navigate(EpisodeRoute(it.id, start)) }
                                            }
                                        }
                                    },
                                ) }
                            }
                            composable<SettingsRoute> { Readable {
                                SettingsScreen(
                                    onWallet = { nav.navigate(WalletRoute) }, onHistory = { nav.navigate(HistoryRoute) }, onStats = { nav.navigate(StatsRoute) },
                                    onPage = { nav.navigate(SettingsPageRoute(it.name)) }, onPrivacy = { nav.navigate(PrivacyRoute) },
                                    onCrashReport = if (crashReport != null) ({ nav.navigate(CrashRoute) }) else null,
                                )
                            } }
                            composable<SettingsPageRoute> { e ->
                                val route = e.toRoute<SettingsPageRoute>()
                                val page = SettingsPage.entries.firstOrNull { it.name == route.page } ?: SettingsPage.About
                                // Opened from the tour: back — the system's or the arrow — goes on with the
                                // tour where it was left, not to an empty Home (maintainer, 2026-10-21).
                                route.fromTour?.let { at ->
                                    val back = remember(e.id) { { if (nav.currentBackStackEntry?.id == e.id) nav.popBackStack(); tourPage = at; tour = true } }
                                    androidx.compose.ui.backhandler.BackHandler { back() }
                                    DisposableEffect(pageBack, back) {
                                        pageBack.step = back
                                        onDispose { if (pageBack.step === back) pageBack.step = null }
                                    }
                                }
                                Readable { SettingsPageScreen(page, onTour = { tourPage = 0; tour = true }, onPage = { nav.navigate(SettingsPageRoute(it.name)) }) }
                            }
                            composable<PrivacyRoute> { Readable { PrivacyScreen(onPage = { nav.navigate(SettingsPageRoute(it.name)) }, onWallet = { nav.navigate(WalletRoute) }) } }
                            composable<CrashRoute> { Readable {
                                CrashReportScreen(crashReport, onDelete = {
                                    crashes.clear(); crashReport = null
                                    if (nav.currentBackStackEntry?.destination?.hasRoute(CrashRoute::class) == true) nav.popBackStack()
                                })
                            } }
                            composable<WalletRoute> { Readable { WalletScreen() } }
                            composable<HistoryRoute> { Readable { HistoryScreen() } }
                            composable<PersonRoute> { e ->
                                val route = e.toRoute<PersonRoute>()
                                Readable(LIST_WIDTH) { space.sovereigncast.hopp.ui.library.PersonScreen(
                                    space.sovereigncast.hopp.ui.library.PersonRef(route.name, route.img, route.href, route.role, route.show),
                                    onEpisode = { nav.navigate(EpisodeRoute(it)) },
                                    // Another show's episode: its preview, the episode on top — as a shared link opens it.
                                    onPreview = { url, guid, title, image -> nav.navigate(ShowPreviewRoute(url, title, image, episodeGuid = guid)) },
                                ) }
                            }
                            composable<StatsRoute> { Readable { space.sovereigncast.hopp.ui.library.StatsScreen() } }
                            composable<ShowRoute> { e ->
                                val route = e.toRoute<ShowRoute>()
                                // Only this page leaves, and only once — the show can vanish while
                                // the page waits in another tab's stack.
                                // Wide: the show and an episode side by side (ADR 0020, step 2).
                                space.sovereigncast.hopp.ui.library.ShowListDetail(
                                    route.id,
                                    show = {
                                        ShowScreen(route.id, onOpenWallet = { nav.navigate(WalletRoute) }, onGone = { if (nav.currentBackStackEntry?.id == e.id) nav.popBackStack() },
                                            openCommunity = route.community, openBoosts = route.boosts, openRound = route.round, openUnlock = route.unlock,
                                            onBoostableAlternative = { alt -> nav.navigate(ShowPreviewRoute(alt.feedUrl, alt.title, alt.imageUrl, alt.author)) })
                                    },
                                    episode = { id, onGone ->
                                        Readable { EpisodeScreen(id, onShow = {}, onGone = onGone, onOpenWallet = { nav.navigate(WalletRoute) }) }
                                    },
                                )
                            }
                        }
                    }
                    if (paneOpen) {
                        androidx.compose.material3.VerticalDivider()
                        Box(Modifier.width(paneWidth).fillMaxHeight()) {
                            NowPlayingScreen(player, inPane = true, onOpenWallet = { player.minimize(); nav.navigate(WalletRoute) })
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
                        expanded && !playerPane,
                        enter = slideInVertically(tween(420, easing = EmphasizedDecelerate)) { it } + fadeIn(tween(180)),
                        exit = slideOutVertically(tween(280, easing = EmphasizedAccelerate)) { it } + fadeOut(tween(200, delayMillis = 80)),
                    ) {
                        NowPlayingScreen(player, onOpenWallet = { player.minimize(); nav.navigate(WalletRoute) })
                    }
                    // Leaves the tour for a page; back on that page comes back to the tour at [at].
                    fun leaveTour(page: SettingsPage, at: Int) {
                        tour = false
                        tourScope.launch { settingsRepo.update { it.copy(onboardingDone = true) } }
                        nav.navigate(SettingsPageRoute(page.name, fromTour = at))
                    }
                    if (tour || appSettings?.onboardingDone == false) OnboardingScreen(
                        hasWallet = connectedWallet != null,
                        initialPage = tourPage,
                        // Set up Nostr from the tour (a wallet can follow any time).
                        onNostr = { at -> leaveTour(SettingsPage.Nostr, at) },
                        // Used Hopp before: the Nostr page with the ways in for an existing account open.
                        onRestore = { at ->
                            space.sovereigncast.hopp.ui.identity.NostrSignInRequest.request()
                            leaveTour(SettingsPage.Nostr, at)
                        },
                        // From another podcast app: their shows over, by OPML (maintainer, 2026-10-21).
                        onMove = { at -> leaveTour(SettingsPage.Subscriptions, at) },
                    ) { connectWallet ->
                        tourPage = 0
                        tour = false
                        tourScope.launch { settingsRepo.update { it.copy(onboardingDone = true) } }
                        if (connectWallet) nav.navigate(WalletRoute)
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
