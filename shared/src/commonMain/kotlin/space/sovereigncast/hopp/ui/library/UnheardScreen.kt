package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.home_unheard_none
import space.sovereigncast.hopp.resources.home_unheard_none_text

/** Per show the newest episode not heard yet (device feedback 2026-10-12) — "More" from Home. */
@Composable
fun UnheardScreen(vm: UnheardViewModel = koinViewModel()) {
    val nav = LocalEpisodeNav.current
    val items by vm.items.collectAsState()
    val list = items ?: return
    if (list.isEmpty()) {
        EmptyState(stringResource(Res.string.home_unheard_none), stringResource(Res.string.home_unheard_none_text))
        return
    }
    LazyColumn {
        items(list, key = { it.id }) { item ->
            EpisodeRow(item, showPodcast = true, onClick = { nav.open(item) }) { PlayButton(item); EpisodeMenu(item) }
        }
    }
}
