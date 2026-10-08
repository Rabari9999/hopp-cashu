package space.sovereigncast.hopp.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform
import space.sovereigncast.hopp.appInfo
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.player.PlayerController

/**
 * Owns the player, so playback outlives the activity: notification, lock
 * screen, Bluetooth and headset buttons come from the session, and Android
 * Auto browses it as a media library (ADR 0012).
 */
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val scope = MainScope()
    private val koin get() = KoinPlatform.getKoin()
    private val controller: PlayerController by lazy { koin.get() }

    override fun onCreate() {
        super.onCreate()
        // Hopp's User-Agent on the audio requests too (ADR 0015): Media3's
        // default "ExoPlayerLib/…" would land in the hosts' statistics as "Other".
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(appInfo(this).userAgent)
            .setAllowCrossProtocolRedirects(true)
        val exo = ExoPlayer.Builder(this)
            // DefaultDataSource: file:// for downloads, http(s) through the factory above.
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        // Skip silence (ADR 0012) is ExoPlayer's own; the setting follows live.
        scope.launch { koin.get<SettingsRepository>().settings.collect { exo.skipSilenceEnabled = it.skipSilence } }

        val tree = MediaTree(this, koin.get(), koin.get(), koin.get(), koin.get())
        session = MediaLibrarySession.Builder(this, ChapterPlayer(exo), Callback(tree)).build()
        // The controller follows whatever plays — also when only the car is
        // using Hopp and the app's UI never started.
        scope.launch { controller }
    }

    /** Next/previous on headsets, the notification and in the car jump by
     *  chapter, past the last one to the next episode in the queue (ADR 0012). */
    private inner class ChapterPlayer(player: Player) : ForwardingPlayer(player) {
        private val extra = listOf(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon().addAll(*extra.toIntArray()).build()
        override fun isCommandAvailable(command: Int) = command in extra || super.isCommandAvailable(command)
        override fun seekToNext() = controller.next()
        override fun seekToNextMediaItem() = controller.next()
        override fun seekToPrevious() = controller.previous()
        override fun seekToPreviousMediaItem() = controller.previous()
    }

    private inner class Callback(private val tree: MediaTree) : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future { LibraryResult.ofItem(tree.root(), params) }

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val all = tree.children(parentId)
            val from = (page.toLong() * pageSize).coerceAtMost(all.size.toLong()).toInt()
            val to = (from.toLong() + pageSize).coerceAtMost(all.size.toLong()).toInt()
            LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        /** Hopp's own engine sends a full item; a car or a browser only the id. */
        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
            startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
            val first = mediaItems.getOrNull(startIndex.coerceAtLeast(0)) ?: mediaItems.first()
            if (first.localConfiguration != null) return@future MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            val (item, position) = tree.resolve(first.mediaId) ?: error("unknown media id ${first.mediaId}")
            MediaItemsWithStartPosition(listOf(item), 0, position)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
            val (item, position) = tree.lastPlayed()?.let { tree.resolve(it) } ?: error("nothing to resume")
            MediaItemsWithStartPosition(listOf(item), 0, position)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // Swiping the app away while paused ends the service; while playing it keeps going.
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        scope.cancel()
        super.onDestroy()
    }
}
