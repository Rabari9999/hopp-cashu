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
    /** Asked to play, still loading — a stream's start on a slow network or a stall. */
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
    /** Nothing loaded any more: the item, and what the system shows of it (lock screen,
     *  notification, Media Session), gone; [state] reports no item. */
    fun unload()
    fun release()
    /** ExoPlayer has it built in; on iOS it needs own audio processing
     *  (ADR 0012) — the setting is hidden there. */
    val supportsSkipSilence: Boolean get() = false
    /** Louder than the file (maintainer 2026-10-21): Android's LoudnessEnhancer; AVPlayer
     *  stops at the file's own volume — hidden on iOS, like skipping silence. */
    val supportsVolumeBoost: Boolean get() = false
    /** The listener's skip intervals, for skip buttons outside the app (the
     *  lock screen, headphones) — Android's session reads them itself. */
    fun setSkipIntervals(backSeconds: Int, forwardSeconds: Int) = Unit
    /** The player's own volume, 0–1, where it has one to offer — the browser, whose page has no
     *  volume keys at hand (UX review 2026-10-30); null where the system's volume is the way
     *  (the apps, Safari on iOS, which keeps an audio element at full volume). */
    val volume: StateFlow<Float>? get() = null
    fun setVolume(level: Float) = Unit
}

/** Identifies Hopp to feed hosts, players and APIs (ADR 0015):
 *  `Hopp/<version> (<Android|iOS>; <Phone|Tablet>)`. */
data class AppInfo(val version: String, val platform: String, val deviceClass: String) {
    val userAgent: String get() = "Hopp/$version ($platform; $deviceClass)"
    /** On iOS — where the system's names and settings are Apple's, not Google's. */
    val isIos: Boolean get() = platform == "iOS"
}
