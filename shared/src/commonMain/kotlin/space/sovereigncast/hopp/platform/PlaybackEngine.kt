package space.sovereigncast.hopp.platform

import kotlinx.coroutines.flow.StateFlow

/** What the platform player is asked to play. */
data class PlaybackItem(
    val id: String,            // episode guid
    val url: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val startPositionMs: Long = 0,
)

data class PlaybackState(
    val itemId: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val error: String? = null,
)

/**
 * The player behind Hopp (ADR 0013): Media3 in a MediaSessionService on
 * Android, AVPlayer on iOS. Everything above this interface — chapters,
 * streaming accrual, the now-playing screen — is common code.
 */
interface PlaybackEngine {
    val state: StateFlow<PlaybackState>
    fun load(item: PlaybackItem, playWhenReady: Boolean)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    fun release()
}

/** Identifies Hopp to feed hosts, players and APIs (ADR 0015):
 *  `Hopp/<version> (<Android|iOS>; <Phone|Tablet>)`. */
data class AppInfo(val version: String, val platform: String, val deviceClass: String) {
    val userAgent: String get() = "Hopp/$version ($platform; $deviceClass)"
}
