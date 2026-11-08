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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Slider
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import space.sovereigncast.hopp.resources.np_open
import space.sovereigncast.hopp.resources.np_progress
import space.sovereigncast.hopp.resources.np_speed
import space.sovereigncast.hopp.resources.np_mute
import space.sovereigncast.hopp.resources.np_unmute
import space.sovereigncast.hopp.resources.np_volume
import space.sovereigncast.hopp.resources.np_pause
import space.sovereigncast.hopp.resources.np_play
import space.sovereigncast.hopp.ui.components.HoppIcons
import space.sovereigncast.hopp.ui.components.formatTime
import space.sovereigncast.hopp.player.PlayerController
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.Icons
import space.sovereigncast.hopp.resources.sc_clip_close
import space.sovereigncast.hopp.resources.sc_clip_now
import space.sovereigncast.hopp.resources.trailer_now
import space.sovereigncast.hopp.player.RemoteClip
import space.sovereigncast.hopp.ui.theme.Hopp
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import space.sovereigncast.hopp.platform.PlaybackState
import space.sovereigncast.hopp.resources.np_buffering

/** The mini player above the tab bar (ADR 0019): tap or swipe up to expand. [last]: nothing below it
 *  (a rail beside instead of a tab bar), so it keeps clear of the system's navigation itself — a
 *  tablet's taskbar or gesture bar covered the player bar (review 2026-11-04). */
@Composable
fun MiniPlayer(model: PlayerController, last: Boolean = false) {
    val foot = if (last) Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)) else Modifier
    val clip by model.remoteClip.collectAsState()
    clip?.let { ClipMiniPlayer(model, it, foot); return }
    val now by model.current.collectAsState()
    val playing = now ?: return
    val episode = playing.item
    val state by model.state.collectAsState()
    val colors = MaterialTheme.colorScheme
    val duration = state.durationMs.takeIf { it > 0 } ?: ((episode.durationSeconds ?: 0) * 1000)
    val track = Hopp.colors.progressTrack
    val fill = Hopp.colors.progressFill ?: colors.onSurface
    // A large window: a player bar instead — seek, speed and, in a browser, volume at hand (UX review 2026-10-30).
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= space.sovereigncast.hopp.ui.components.EXPANDED_WIDTH) { PlayerBar(model, playing, state, duration, foot); return@BoxWithConstraints }
    Column(
        Modifier.fillMaxWidth().background(colors.surface).then(foot)
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
                    PlayPause(state, colors.onSurface, 24.dp)
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
                    PlayPause(state, colors.onPrimary, 26.dp)
                }
                IconButton(onClick = model::skipForward) {
                    Icon(HoppIcons.Forward, stringResource(Res.string.np_forward30, settings.skipForwardSeconds), Modifier.size(28.dp))
                }
            }
        }
    }
    }
}

/**
 * The player as a bar along the window's foot (UX review 2026-10-30, as music and podcast players
 * on a desktop): what plays, the skips round play, a seek bar with the chapters and the times,
 * the speed, the volume where the engine has one of its own (the browser), and the way up to
 * now playing. A tap on the cover or the title opens it too.
 */
@Composable
private fun PlayerBar(model: PlayerController, playing: space.sovereigncast.hopp.player.NowPlaying, state: PlaybackState, duration: Long, foot: Modifier) {
    val colors = MaterialTheme.colorScheme
    val episode = playing.item
    val settings by model.settings.collectAsState()
    val chapters = playing.chapters
    val position = state.positionMs
    Column(Modifier.fillMaxWidth().background(colors.surface).then(foot)) {
        HorizontalDivider(color = colors.outlineVariant)
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.width(300.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = stringResource(Res.string.np_open)) { model.expand() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = chapters.imageAt(position) ?: playing.artworkUrl, contentDescription = null,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(episode.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(chapters.current(position)?.title?.takeIf { it.isNotBlank() } ?: playing.podcastTitle,
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = model::skipBack) {
                        Icon(HoppIcons.Replay, stringResource(Res.string.np_back15, settings.skipBackSeconds), Modifier.size(24.dp))
                    }
                    Box(
                        Modifier.padding(horizontal = 12.dp).size(44.dp).clip(CircleShape).background(colors.primary).clickable { model.togglePlay() },
                        contentAlignment = Alignment.Center,
                    ) {
                        PlayPause(state, colors.onPrimary, 22.dp)
                    }
                    IconButton(onClick = model::skipForward) {
                        Icon(HoppIcons.Forward, stringResource(Res.string.np_forward30, settings.skipForwardSeconds), Modifier.size(24.dp))
                    }
                }
                var dragTo by remember { mutableStateOf<Float?>(null) }
                val shownAt = dragTo?.let { (it * duration).toLong() } ?: position
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(shownAt), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    space.sovereigncast.hopp.ui.components.ChapterProgressBar(
                        fraction = if (duration > 0) position.toFloat() / duration else 0f,
                        boundaries = chapters.boundaryFractions(duration),
                        onSeek = { f -> model.seekTo((f * duration).toLong()) },
                        trackColor = Hopp.colors.progressTrack, fillColor = Hopp.colors.progressFill ?: colors.onSurface, gapColor = colors.surface,
                        label = stringResource(Res.string.np_progress),
                        preview = { f ->
                            val at = (f * duration).toLong()
                            listOfNotNull(formatTime(at), chapters.current(at)?.title?.takeIf { it.isNotBlank() }).joinToString(" · ")
                        },
                        onPreview = { dragTo = it },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("−" + formatTime((duration - shownAt).coerceAtLeast(0)), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(20.dp))
            val speedLabel = stringResource(Res.string.np_speed)
            val speedText = "${formatSpeed(state.speed)}×"
            Text(
                speedText,
                Modifier.clip(RoundedCornerShape(50)).clickable { model.cycleSpeed() }.padding(10.dp)
                    .semantics { contentDescription = speedLabel; stateDescription = speedText },
                fontWeight = FontWeight.Bold,
            )
            VolumeControl(model, Modifier.width(110.dp))
            IconButton(onClick = model::expand) { Icon(HoppIcons.ChevronUp, stringResource(Res.string.np_open)) }
        }
    }
}

/** The player's own volume where it has one (a browser): mute and a slider, in the bar along a wide
 *  window's foot alone (maintainer 2026-11-02; the open player had it from 2026-10-31). In a Row. */
@Composable
internal fun VolumeControl(model: PlayerController, sliderModifier: Modifier) {
    val flow = model.engine.volume ?: return
    val volume by flow.collectAsState()
    var before by remember { mutableStateOf(1f) }
    IconButton(onClick = { if (volume > 0f) { before = volume; model.engine.setVolume(0f) } else model.engine.setVolume(before.takeIf { it > 0f } ?: 1f) }) {
        Icon(if (volume > 0f) HoppIcons.Volume else HoppIcons.VolumeOff, stringResource(if (volume > 0f) Res.string.np_mute else Res.string.np_unmute))
    }
    val volumeLabel = stringResource(Res.string.np_volume)
    Slider(volume, onValueChange = model.engine::setVolume, modifier = sliderModifier.semantics { contentDescription = volumeLabel })
}

/** A Sovereigncast clip playing (phase 2): what it is, play/pause, and closing
 *  it — then the episode from before is back. It has no now-playing screen. */
@Composable
private fun ClipMiniPlayer(model: PlayerController, clip: RemoteClip, foot: Modifier) {
    val state by model.state.collectAsState()
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(colors.surface).then(foot)) {
        HorizontalDivider(color = colors.outlineVariant)
        val track = Hopp.colors.progressTrack
        val fill = Hopp.colors.value
        Canvas(Modifier.fillMaxWidth().height(3.dp)) {
            drawRect(track, Offset.Zero, size)
            val f = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
            drawRect(fill, Offset.Zero, Size(size.width * f, size.height))
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = clip.artworkUrl, contentDescription = null,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (clip.trailer) Res.string.trailer_now else Res.string.sc_clip_now, clip.title), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(clip.showTitle, "${formatTime(state.positionMs)} / ${formatTime(state.durationMs)}").filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { if (state.isPlaying) model.engine.pause() else model.engine.play() }, contentAlignment = Alignment.Center) {
                Icon(
                    if (state.isPlaying) HoppIcons.Pause else HoppIcons.Play,
                    stringResource(if (state.isPlaying) Res.string.np_pause else Res.string.np_play),
                    tint = colors.onSurface,
                )
            }
            IconButton(onClick = model::closeRemoteClip) {
                Icon(Icons.Rounded.Close, stringResource(Res.string.sc_clip_close), tint = colors.onSurfaceVariant)
            }
        }
    }
}

/**
 * Play or pause — and while a stream loads, a ring turning round it (device
 * feedback 2026-10-19: up to eight seconds on mobile data before a word, and
 * the button looked as if the tap had missed). Loading counts as playing:
 * pause shows, and a tap stops it rather than asking again. Inside the
 * button's box, which the ring fills.
 */
@Composable
internal fun BoxScope.PlayPause(state: PlaybackState, tint: Color, iconSize: Dp) {
    if (state.isBuffering) {
        val loading = stringResource(Res.string.np_buffering)
        CircularProgressIndicator(
            Modifier.matchParentSize().padding(3.dp).semantics { contentDescription = loading },
            color = tint, strokeWidth = 2.5.dp,
        )
    }
    val going = state.isPlaying || state.isBuffering
    Icon(
        if (going) HoppIcons.Pause else HoppIcons.Play,
        stringResource(if (going) Res.string.np_pause else Res.string.np_play),
        tint = tint, modifier = Modifier.size(iconSize),
    )
}
