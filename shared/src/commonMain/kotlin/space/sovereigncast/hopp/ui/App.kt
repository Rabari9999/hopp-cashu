package space.sovereigncast.hopp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import org.jetbrains.compose.resources.stringResource
import space.sovereigncast.hopp.net.createHttpClient
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.platform.PlaybackEngine
import space.sovereigncast.hopp.platform.PositionStore
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.app_tagline
import space.sovereigncast.hopp.ui.spike.EpisodeListScreen
import space.sovereigncast.hopp.ui.spike.MiniPlayer
import space.sovereigncast.hopp.ui.spike.NowPlayingScreen
import space.sovereigncast.hopp.ui.spike.PlaybackSpikeModel
import space.sovereigncast.hopp.ui.theme.HoppTheme

/** What the platform hands to the shared UI. */
class AppDependencies(val app: AppInfo, val engine: PlaybackEngine, val positions: PositionStore)

/** Phase 0: the playback spike when the platform provides a player (Android),
 *  the placeholder otherwise (iOS until its spike). */
@Composable
fun App(deps: AppDependencies? = null) {
    HoppTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (deps == null) Placeholder() else PlaybackSpike(deps)
        }
    }
}

@Composable
private fun PlaybackSpike(deps: AppDependencies) {
    val http = remember { createHttpClient(deps.app) }
    // Images through the same client: same User-Agent as everything else.
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context).components { add(KtorNetworkFetcherFactory(http)) }.build()
    }
    val scope = rememberCoroutineScope()
    val model = remember { PlaybackSpikeModel(http, deps.engine, deps.positions, scope) }
    DisposableEffect(Unit) { onDispose { http.close() } }
    val ui by model.ui.collectAsState()
    if (ui.selected != null && ui.expanded) NowPlayingScreen(model, ui)
    else EpisodeListScreen(ui, onSelect = { model.select(it) }, onRetry = model::loadFeed) {
        MiniPlayer(model, ui)
    }
}

@Composable
private fun Placeholder() {
    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("hopp", style = MaterialTheme.typography.displayMedium)
        Text(stringResource(Res.string.app_tagline), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
