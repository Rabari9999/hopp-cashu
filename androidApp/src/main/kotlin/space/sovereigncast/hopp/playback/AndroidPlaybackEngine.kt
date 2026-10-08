package space.sovereigncast.hopp.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import space.sovereigncast.hopp.platform.PlaybackEngine
import space.sovereigncast.hopp.platform.PlaybackItem
import space.sovereigncast.hopp.platform.PlaybackState

/** [PlaybackEngine] on Android: a MediaController talking to [PlaybackService]. */
class AndroidPlaybackEngine(context: Context) : PlaybackEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        scope.launch {
            val c = MediaController.Builder(context, token).buildAsync().await()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = publish()
                override fun onPlayerError(error: PlaybackException) {
                    _state.value = _state.value.copy(error = error.errorCodeName + ": " + (error.message ?: ""))
                }
            })
            pending.forEach { it(c) }; pending.clear()
            publish()
            // Position ticks while playing — Player.Listener does not report progress.
            while (isActive) { if (c.isPlaying) publish(); delay(500) }
        }
    }

    private fun publish() {
        val c = controller ?: return
        _state.value = PlaybackState(
            itemId = c.currentMediaItem?.mediaId,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it > 0 } ?: 0,
            speed = c.playbackParameters.speed,
            error = _state.value.error.takeIf { c.playerError != null },
        )
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let(block) ?: pending.add(block)
    }

    override fun load(item: PlaybackItem, playWhenReady: Boolean) = withController { c ->
        val media = MediaItem.Builder()
            .setMediaId(item.id)
            .setUri(item.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title).setArtist(item.artist)
                    .setArtworkUri(item.artworkUrl?.let(Uri::parse))
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                    .build(),
            ).build()
        c.setMediaItem(media, item.startPositionMs)
        c.prepare()
        c.playWhenReady = playWhenReady
    }

    override fun play() = withController { it.play() }
    override fun pause() = withController { it.pause() }
    override fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs); publish() }
    override fun setSpeed(speed: Float) = withController { it.setPlaybackSpeed(speed) }

    // Applied by PlaybackService from the settings — ExoPlayer's own feature.
    override val supportsSkipSilence: Boolean = true

    /** Releases the controller only; the service keeps playing. */
    override fun release() {
        controller?.release()
        controller = null
        scope.cancel()
    }
}
