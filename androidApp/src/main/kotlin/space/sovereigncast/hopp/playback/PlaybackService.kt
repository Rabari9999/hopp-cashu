package space.sovereigncast.hopp.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import space.sovereigncast.hopp.R
import space.sovereigncast.hopp.value.QuickBoost
import space.sovereigncast.hopp.value.ValueService
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
    }

    private var boostShown = false
    private val boostCommand = SessionCommand(ACTION_BOOST, Bundle.EMPTY)

    private fun buttons(): List<CommandButton> = if (!boostShown) emptyList() else listOf(
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(R.drawable.ic_boost)
            .setDisplayName(getString(R.string.quick_boost))
            .setSessionCommand(boostCommand)
            .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build(),
    )

    /** One tap, the set amount, no dialog (device feedback 2026-10-08) — and
     *  a notification that says what happened. */
    private fun quickBoost() {
        val now = controller.current.value?.item ?: return
        val position = controller.state.value.positionMs / 1000
        scope.launch {
            val r = koin.get<ValueService>().quickBoost(now, position)
            // What to report is the listener's choice (device feedback 2026-10-10):
            // nothing, only what went wrong (default), or everything.
            val ok = r is QuickBoost.Sent && r.paidSats == r.amountSats
            if (!koin.get<SettingsRepository>().current().reportsQuickBoost(ok)) return@launch
            val text = when (r) {
                is QuickBoost.Sent -> if (r.paidSats == r.amountSats) getString(R.string.quick_boost_sent, r.paidSats.toString(), r.show)
                    else getString(R.string.quick_boost_partial, r.paidSats.toString(), r.amountSats.toString(), r.show)
                QuickBoost.OverBudget -> getString(R.string.quick_boost_budget)
                QuickBoost.NoValue -> getString(R.string.quick_boost_no_value)
                QuickBoost.NoWallet -> getString(R.string.quick_boost_no_wallet)
                is QuickBoost.Failed -> getString(R.string.quick_boost_failed, r.reason)
                QuickBoost.Off -> return@launch
            }
            notifyBoost(text, ok)
        }
    }

    private fun notifyBoost(text: String, ok: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(BOOST_CHANNEL, getString(R.string.boosts_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val n = NotificationCompat.Builder(this, BOOST_CHANNEL)
            .setSmallIcon(R.drawable.ic_boost)
            .setContentTitle(getString(R.string.quick_boost))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            // A success needs no keeping: it goes by itself after a few seconds.
            .apply { if (ok) setTimeoutAfter(6_000) }
            .build()
        runCatching { manager.notify(BOOST_NOTIFICATION, n) }
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
        /** Every controller — notification, lock screen, car — may send the boost. */
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().add(boostCommand).build())
                .setMediaButtonPreferences(buttons())
                .build()

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_BOOST) quickBoost()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

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

    private companion object {
        const val ACTION_BOOST = "space.sovereigncast.hopp.BOOST"
        const val BOOST_CHANNEL = "boosts"
        const val BOOST_NOTIFICATION = 3
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
