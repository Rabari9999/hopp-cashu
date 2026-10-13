package space.sovereigncast.hopp.ui.player

import space.sovereigncast.hopp.resources.np_forward30
import space.sovereigncast.hopp.resources.np_back15
import space.sovereigncast.hopp.ui.components.MEDIUM_WIDTH
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.stringResource
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.np_pause
import space.sovereigncast.hopp.resources.np_play
import space.sovereigncast.hopp.ui.components.HoppIcons
import space.sovereigncast.hopp.ui.components.formatTime
import space.sovereigncast.hopp.player.PlayerController
import space.sovereigncast.hopp.ui.theme.Hopp

/** The mini player above the tab bar (ADR 0019): tap or swipe up to expand. */
@Composable
fun MiniPlayer(model: PlayerController) {
    val now by model.current.collectAsState()
    val playing = now ?: return
    val episode = playing.item
    val state by model.state.collectAsState()
    val colors = MaterialTheme.colorScheme
    val duration = state.durationMs.takeIf { it > 0 } ?: ((episode.durationSeconds ?: 0) * 1000)
    val track = Hopp.colors.progressTrack
    val fill = colors.onSurface
    Column(
        Modifier.fillMaxWidth().background(colors.surface)
            .clickable { model.expand() }
            .pointerInput(Unit) { detectVerticalDragGestures { change, dy -> if (dy < -10) { change.consume(); model.expand() } } },
    ) {
        HorizontalDivider(color = colors.outlineVariant)
        Canvas(Modifier.fillMaxWidth().height(3.dp)) {
            drawRect(track, Offset.Zero, size)
            val f = if (duration > 0) (state.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
            drawRect(fill, Offset.Zero, Size(size.width * f, size.height))
        }
        // Taller from a tablet's width (device feedback 2026-10-13, ADR 0020): a
        // bigger cover, the show's name, and the skips around a bigger play button.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < MEDIUM_WIDTH) Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = playing.artworkUrl, contentDescription = null,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(episode.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${formatTime(state.positionMs)} / ${formatTime(duration)}",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    )
                }
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable { model.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (state.isPlaying) HoppIcons.Pause else HoppIcons.Play,
                        stringResource(if (state.isPlaying) Res.string.np_pause else Res.string.np_play),
                        tint = colors.onSurface,
                    )
                }
            } else Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val settings by model.settings.collectAsState()
                AsyncImage(
                    model = playing.artworkUrl, contentDescription = null,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(episode.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(playing.podcastTitle, "${formatTime(state.positionMs)} / ${formatTime(duration)}").filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = model::skipBack) {
                    Icon(HoppIcons.Replay, stringResource(Res.string.np_back15, settings.skipBackSeconds), Modifier.size(28.dp))
                }
                Box(
                    Modifier.padding(horizontal = 8.dp).size(52.dp).clip(CircleShape).background(colors.primary).clickable { model.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (state.isPlaying) HoppIcons.Pause else HoppIcons.Play,
                        stringResource(if (state.isPlaying) Res.string.np_pause else Res.string.np_play),
                        tint = colors.onPrimary, modifier = Modifier.size(26.dp),
                    )
                }
                IconButton(onClick = model::skipForward) {
                    Icon(HoppIcons.Forward, stringResource(Res.string.np_forward30, settings.skipForwardSeconds), Modifier.size(28.dp))
                }
            }
        }
    }
}
