package space.sovereigncast.hopp.playback

import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import space.sovereigncast.hopp.widget.WidgetNow
import space.sovereigncast.hopp.widget.WidgetState
import space.sovereigncast.hopp.R
import space.sovereigncast.hopp.value.WalletRepository
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
import space.sovereigncast.hopp.data.Settings
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
            .build()
        // Skip silence (ADR 0012) is ExoPlayer's own; the setting follows live,
        // as do the skip intervals ([ChapterPlayer]).
        scope.launch {
            koin.get<SettingsRepository>().settings.collect {
                exo.skipSilenceEnabled = it.skipSilence
                louder.on = it.volumeBoost
                val changed = skipBackMs != it.skipBackSeconds * 1000L || skipForwardMs != it.skipForwardSeconds * 1000L
                skipBackMs = it.skipBackSeconds * 1000L
                skipForwardMs = it.skipForwardSeconds * 1000L
                // The lock screen's skip buttons show the seconds.
                if (changed) session?.setMediaButtonPreferences(buttons())
            }
        }

        // The volume boost works on the player's audio session — a new one, and it moves along.
        louder.attach(exo.audioSessionId)
        exo.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) = louder.attach(audioSessionId)
        })

        val tree = MediaTree(this, koin.get(), koin.get(), koin.get(), koin.get())
        // The skip buttons from the start; the boost joins once the wallet is known (below).
        session = MediaLibrarySession.Builder(this, ChapterPlayer(exo), Callback(tree).also { callback = it })
            .setMediaButtonPreferences(buttons())
            .build()
        // The boost button on the lock screen and in the notification — only
        // with a wallet and a quick-boost amount set.
        scope.launch {
            combine(koin.get<WalletRepository>().wallet, koin.get<SettingsRepository>().settings) { w, s -> w != null && s.quickBoostSats > 0 }
                .distinctUntilChanged()
                .collect { show -> boostShown = show; session?.setMediaButtonPreferences(buttons()) }
        }
        // The controller follows whatever plays — also when only the car is
        // using Hopp and the app's UI never started.
        scope.launch { controller }
        // The home-screen widget (0.3.1) shows what plays: on every change of episode, play state,
        // chapter or boost, and while playing every half minute — not with each position report.
        scope.launch {
            combine(controller.current, controller.state, koin.get<SettingsRepository>().settings, koin.get<WalletRepository>().wallet) { c, st, s, w ->
                c?.let {
                    val position = st.positionMs / WIDGET_STEP_MS * WIDGET_STEP_MS
                    WidgetNow(it.item.title, it.podcastTitle, it.chapters.current(st.positionMs)?.title, it.artworkUrl, position,
                        st.durationMs.takeIf { d -> d > 0 } ?: ((it.item.durationSeconds ?: 0) * 1000), st.isPlaying || st.isBuffering,
                        s.quickBoostSats.takeIf { sats -> sats > 0 && w != null }, s.skipBackSeconds, s.skipForwardSeconds)
                }
            }.distinctUntilChanged().collect { WidgetState.show(this@PlaybackService, it) }
        }
    }

    /** The session's callback — for the tests that check who may boost. */
    internal var callback: Callback? = null
        private set

    private var boostShown = false
    private val louder = Louder()
    private var skipBackMs = Settings().skipBackSeconds * 1000L
    private var skipForwardMs = Settings().skipForwardSeconds * 1000L
    private val boostCommand = SessionCommand(ACTION_BOOST, Bundle.EMPTY)
    private val nextChapterCommand = SessionCommand(ACTION_NEXT_CHAPTER, Bundle.EMPTY)

    /**
     * The lock screen and the notification (maintainer, 2026-10-21): skip back
     * and forward by the listener's seconds where previous and next were, the
     * boost beside them — with a wallet — and the next chapter after it: a command of its own,
     * as the media keys' next and previous skip by seconds ([ChapterPlayer]).
     */
    private fun buttons(withBoost: Boolean = boostShown): List<CommandButton> = buildList {
        val back = (skipBackMs / 1000).toInt()
        val forward = (skipForwardMs / 1000).toInt()
        add(
            CommandButton.Builder(when (back) {
                5 -> CommandButton.ICON_SKIP_BACK_5; 10 -> CommandButton.ICON_SKIP_BACK_10
                15 -> CommandButton.ICON_SKIP_BACK_15; 30 -> CommandButton.ICON_SKIP_BACK_30
                else -> CommandButton.ICON_SKIP_BACK
            })
                .setDisplayName(getString(R.string.skip_back, back))
                .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                .setSlots(CommandButton.SLOT_BACK)
                .build(),
        )
        add(
            CommandButton.Builder(when (forward) {
                5 -> CommandButton.ICON_SKIP_FORWARD_5; 10 -> CommandButton.ICON_SKIP_FORWARD_10
                15 -> CommandButton.ICON_SKIP_FORWARD_15; 30 -> CommandButton.ICON_SKIP_FORWARD_30
                else -> CommandButton.ICON_SKIP_FORWARD
            })
                .setDisplayName(getString(R.string.skip_forward, forward))
                .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                .setSlots(CommandButton.SLOT_FORWARD)
                .build(),
        )
        if (withBoost) add(
            CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                .setCustomIconResId(R.drawable.ic_boost)
                .setDisplayName(getString(R.string.quick_boost))
                .setSessionCommand(boostCommand)
                .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
                .build(),
        )
        add(
            CommandButton.Builder(CommandButton.ICON_NEXT)
                .setDisplayName(getString(R.string.next_chapter))
                .setSessionCommand(nextChapterCommand)
                .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
                .build(),
        )
    }

    /** One tap, the set amount, no dialog (device feedback 2026-10-08) — and
     *  a notification that says what happened, as the listener chose
     *  (device feedback 2026-10-10: nothing, only failures, or everything). */
    private fun quickBoost() { QuickBoost.send(scope) }

    /**
     * Who may send the boost — money leaves the wallet with it (security
     * report 2026-10-10, H-01: the session is exported, and any app could
     * connect and send the command): Hopp itself, its media notification,
     * Android Auto, and controllers the system trusts with media control
     * (System UI's lock screen and notification controls; an app with an
     * enabled notification listener, which could press the notification's
     * boost button anyway). Every other app gets playback controls only.
     */
    internal fun mayBoost(session: MediaSession, c: MediaSession.ControllerInfo): Boolean =
        c.packageName == packageName || c.isTrusted ||
            session.isMediaNotificationController(c) || session.isAutomotiveController(c) || session.isAutoCompanionController(c)

    /** Back and forward skip by the listener's intervals, as in the app (device feedback
     *  2026-10-14: the notification skipped a fixed 10 and 30 seconds) — and so do next and
     *  previous, the media keys of headsets and the car's steering wheel (maintainer
     *  2026-10-27: "forward" on the wheel jumped to the next episode). The next chapter has
     *  its button of its own ([nextChapterCommand]); ADR 0012. */
    private inner class ChapterPlayer(player: Player) : ForwardingPlayer(player) {
        private val extra = listOf(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon().addAll(*extra.toIntArray()).build()
        override fun isCommandAvailable(command: Int) = command in extra || super.isCommandAvailable(command)
        override fun seekToNext() = seekForward()
        override fun seekToNextMediaItem() = seekForward()
        override fun seekToPrevious() = seekBack()
        override fun seekToPreviousMediaItem() = seekBack()
        override fun getSeekBackIncrement() = skipBackMs
        override fun getSeekForwardIncrement() = skipForwardMs
        override fun seekBack() = seekTo((currentPosition - skipBackMs).coerceAtLeast(0))
        override fun seekForward() = seekTo(currentPosition + skipForwardMs)
    }

    internal inner class Callback(private val tree: MediaTree) : MediaLibrarySession.Callback {
        /** The notification, the lock screen and the car get the boost; other apps the skip buttons only ([mayBoost]). */
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val allowed = mayBoost(session, controller)
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            commands.add(nextChapterCommand)
            if (allowed) commands.add(boostCommand)
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands.build())
                .setMediaButtonPreferences(buttons(withBoost = allowed && boostShown))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_NEXT_CHAPTER) {
                this@PlaybackService.controller.next()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction != ACTION_BOOST) return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            // Checked again here, not only when the command was offered.
            if (!mayBoost(session, controller)) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            quickBoost()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /**
         * Answered at once, never through [scope]: for a media browser of the old API (the system after a restart,
         * the car) Media3 blocks the main thread in onGetRoot until this is done, and [scope] needs that thread. Hopp
         * stood frozen on its splash, not responding (device check 2026-11-06).
         */
        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(tree.root(), params))

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

    private companion object {
        const val ACTION_BOOST = "space.sovereigncast.hopp.BOOST"
        const val ACTION_NEXT_CHAPTER = "space.sovereigncast.hopp.NEXT_CHAPTER"
        /** The widget's time moves in half minutes. */
        const val WIDGET_STEP_MS = 30_000L
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // Swiping the app away while paused ends the service; while playing it keeps going.
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        louder.release()
        session?.run { player.release(); release() }
        session = null
        scope.cancel()
        super.onDestroy()
    }
}

/**
 * The volume boost (maintainer 2026-10-21: some shows and episodes are quiet):
 * Android's LoudnessEnhancer on the player's audio session, [GAIN_MB] louder
 * while [on]. A device without the effect plays as it was.
 */
internal class Louder {
    private var effect: android.media.audiofx.LoudnessEnhancer? = null
    var on = false
        set(value) { field = value; runCatching { effect?.enabled = value } }

    fun attach(audioSessionId: Int) {
        release()
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        effect = runCatching {
            android.media.audiofx.LoudnessEnhancer(audioSessionId).apply { setTargetGain(GAIN_MB); enabled = on }
        }.getOrNull()
    }

    fun release() {
        runCatching { effect?.release() }
        effect = null
    }

    companion object {
        /** In millibels: +8 dB — clearly louder, not yet distorted on speech. */
        const val GAIN_MB = 800
    }
}
