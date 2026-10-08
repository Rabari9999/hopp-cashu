package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import space.sovereigncast.hopp.data.EpisodeItem
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.library_continue
import space.sovereigncast.hopp.resources.library_empty
import space.sovereigncast.hopp.resources.library_shows
import space.sovereigncast.hopp.resources.inbox_no_shows

/** "Library" (ADR 0012): continue listening, then the subscribed shows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onShow: (Long) -> Unit, onPlay: (EpisodeItem) -> Unit, vm: LibraryViewModel = koinViewModel()) {
    val shows by vm.shows.collectAsState()
    val inProgress by vm.inProgress.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = vm::refresh) {
        val list = shows ?: return@PullToRefreshBox
        if (list.isEmpty()) {
            EmptyState(stringResource(Res.string.library_empty), stringResource(Res.string.inbox_no_shows))
            return@PullToRefreshBox
        }
        LazyVerticalGrid(GridCells.Adaptive(104.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (inProgress.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Header(stringResource(Res.string.library_continue)) }
                items(inProgress.take(3), key = { "p${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { item ->
                    EpisodeRow(item, showPodcast = true, onClick = { onPlay(item) })
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Header(stringResource(Res.string.library_shows)) }
            items(list, key = { it.id }) { show ->
                Column(Modifier.padding(8.dp).clickable { onShow(show.id) }) {
                    Cover(show.imageUrl, 104.dp, corner = 16.dp)
                    Text(show.title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) = Text(
    text, style = MaterialTheme.typography.titleMedium,
    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
)
