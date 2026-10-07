package space.sovereigncast.hopp.playback

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.playImmediatelyAtRate
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.setRate
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSURL
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusCommandFailed
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import space.sovereigncast.hopp.platform.PlaybackEngine
import space.sovereigncast.hopp.platform.PlaybackItem
import space.sovereigncast.hopp.platform.PlaybackState

/**
 * [PlaybackEngine] on iOS: AVPlayer with the playback audio session (keeps
 * playing in the background — UIBackgroundModes audio in Info.plist), Now
 * Playing info and remote commands for the lock screen, Control Center and
 * headphones. Phase-0 spike of ADR 0018: the shared core and UI run unchanged
 * on top of it.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPlaybackEngine(private val userAgent: String) : PlaybackEngine {
    private val player = AVPlayer()
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private var current: PlaybackItem? = null
    private var speed = 1f
    private var playWhenReady = false
    private val observer: Any

    init {
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, error = null)
        session.setActive(true, error = null)
        // On the main queue (null) every half second: position for the UI.
        observer = player.addPeriodicTimeObserverForInterval(CMTimeMakeWithSeconds(0.5, 600), null) { _ -> publish() }
        remoteCommands()
    }

    private fun seconds(value: Double): Double = if (value.isNaN() || value.isInfinite()) 0.0 else value

    private fun publish() {
        val position = seconds(CMTimeGetSeconds(player.currentTime()))
        val duration = player.currentItem?.let { seconds(CMTimeGetSeconds(it.duration)) } ?: 0.0
        val playing = player.timeControlStatus == AVPlayerTimeControlStatusPlaying
        _state.value = PlaybackState(
            itemId = current?.id,
            isPlaying = playing || (playWhenReady && player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate),
            isBuffering = player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate,
            positionMs = (position * 1000).toLong(),
            durationMs = (duration * 1000).toLong(),
            speed = speed,
        )
        nowPlaying(position, duration, playing)
    }

    private fun nowPlaying(position: Double, duration: Double, playing: Boolean) {
        val item = current ?: return
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = mapOf<Any?, Any?>(
            MPMediaItemPropertyTitle to item.title,
            MPMediaItemPropertyArtist to item.artist,
            MPMediaItemPropertyPlaybackDuration to duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime to position,
            MPNowPlayingInfoPropertyPlaybackRate to if (playing) speed.toDouble() else 0.0,
        )
    }

    private fun remoteCommands() {
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        center.playCommand.addTargetWithHandler { _ -> play(); MPRemoteCommandHandlerStatusSuccess }
        center.pauseCommand.addTargetWithHandler { _ -> pause(); MPRemoteCommandHandlerStatusSuccess }
        center.togglePlayPauseCommand.addTargetWithHandler { _ ->
            if (_state.value.isPlaying) pause() else play(); MPRemoteCommandHandlerStatusSuccess
        }
        center.skipBackwardCommand.preferredIntervals = listOf(15.0)
        center.skipBackwardCommand.addTargetWithHandler { _ -> seekTo(_state.value.positionMs - 15_000); MPRemoteCommandHandlerStatusSuccess }
        center.skipForwardCommand.preferredIntervals = listOf(30.0)
        center.skipForwardCommand.addTargetWithHandler { _ -> seekTo(_state.value.positionMs + 30_000); MPRemoteCommandHandlerStatusSuccess }
        center.changePlaybackPositionCommand.addTargetWithHandler { event ->
            val e = event as? MPChangePlaybackPositionCommandEvent
            if (e == null) MPRemoteCommandHandlerStatusCommandFailed
            else { seekTo((e.positionTime * 1000).toLong()); MPRemoteCommandHandlerStatusSuccess }
        }
    }

    override fun load(item: PlaybackItem, playWhenReady: Boolean) {
        val url = NSURL.URLWithString(item.url) ?: return
        // Hopp's User-Agent on the audio requests too (ADR 0015): AVPlayer's
        // default "AppleCoreMedia/…" is counted as Apple Podcasts by hosts.
        // AVURLAssetHTTPHeaderFieldsKey is undocumented but widely used — the
        // spike checks that it works.
        val asset = AVURLAsset.URLAssetWithURL(url, mapOf<Any?, Any?>("AVURLAssetHTTPHeaderFieldsKey" to mapOf("User-Agent" to userAgent)))
        current = item
        player.replaceCurrentItemWithPlayerItem(AVPlayerItem.playerItemWithAsset(asset))
        if (item.startPositionMs > 0) player.seekToTime(CMTimeMakeWithSeconds(item.startPositionMs / 1000.0, 600))
        this.playWhenReady = playWhenReady
        if (playWhenReady) play() else publish()
    }

    override fun play() {
        playWhenReady = true
        player.playImmediatelyAtRate(speed)
        publish()
    }

    override fun pause() {
        playWhenReady = false
        player.pause()
        publish()
    }

    override fun seekTo(positionMs: Long) {
        player.seekToTime(CMTimeMakeWithSeconds(positionMs.coerceAtLeast(0) / 1000.0, 600))
        publish()
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed
        if (_state.value.isPlaying) player.setRate(speed)
        publish()
    }

    override fun release() {
        player.pause()
    }
}
