package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.favorites_empty
import space.sovereigncast.hopp.resources.favorites_empty_text

/** Every favourite, newest first (device feedback 2026-10-11) — reached from Home. */
@Composable
fun FavoritesScreen(vm: FavoritesViewModel = koinViewModel()) {
    val nav = LocalEpisodeNav.current
    val items by vm.items.collectAsState()
    val list = items ?: return
    if (list.isEmpty()) {
        EmptyState(stringResource(Res.string.favorites_empty), stringResource(Res.string.favorites_empty_text))
        return
    }
    LazyColumn {
        items(list, key = { it.id }) { item ->
            EpisodeRow(item, showPodcast = true, onClick = { nav.open(item) }) { PlayButton(item); EpisodeMenu(item) }
        }
    }
}
