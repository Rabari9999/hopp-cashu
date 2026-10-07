package space.sovereigncast.hopp.ui.spike

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.stringResource
import space.sovereigncast.hopp.model.Episode
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.spike_error
import space.sovereigncast.hopp.resources.spike_has_chapters
import space.sovereigncast.hopp.resources.spike_loading
import space.sovereigncast.hopp.resources.spike_retry
import space.sovereigncast.hopp.ui.components.formatDate
import space.sovereigncast.hopp.ui.components.formatTime

@Composable
fun EpisodeListScreen(ui: SpikeUi, onSelect: (Episode) -> Unit, onRetry: () -> Unit, miniPlayer: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
            ui.loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.size(12.dp))
                Text(stringResource(Res.string.spike_loading))
            }
            ui.error != null -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(Res.string.spike_error, ui.error))
                Spacer(Modifier.size(12.dp))
                Button(onClick = onRetry) { Text(stringResource(Res.string.spike_retry)) }
            }
            ui.feed != null -> LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = ui.feed.podcast.imageUrl, contentDescription = null,
                            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(ui.feed.podcast.title ?: "", style = MaterialTheme.typography.headlineSmall)
                            Text("${ui.feed.episodes.size} · ${ui.feed.podcast.language ?: ""}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(ui.feed.episodes.take(60), key = { it.guid }) { ep ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(ep) }.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AsyncImage(
                            model = ep.imageUrl ?: ui.feed.podcast.imageUrl, contentDescription = null,
                            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(ep.title ?: ep.guid, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(formatDate(ep.publishedAt), ep.durationSeconds?.let { formatTime(it * 1000) }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (ep.chaptersUrl != null) Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(50)) {
                            Text(stringResource(Res.string.spike_has_chapters), Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 84.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                }
            }
        }
    }
    miniPlayer()
    }
}
