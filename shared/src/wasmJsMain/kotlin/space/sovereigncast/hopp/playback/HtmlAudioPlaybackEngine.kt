package space.sovereigncast.hopp.playback

import kotlinx.browser.document
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.w3c.dom.HTMLAudioElement
import space.sovereigncast.hopp.platform.PlaybackEngine
import space.sovereigncast.hopp.platform.PlaybackItem
import space.sovereigncast.hopp.platform.PlaybackState

private fun hasMediaSession(): Boolean = js("'mediaSession' in navigator")

private fun setMetadata(title: String, artist: String, artwork: String?): Unit =
    js("{ navigator.mediaSession.metadata = new MediaMetadata({ title: title, artist: artist, artwork: artwork ? [{ src: artwork }] : [] }); }")

private fun setAction(action: String, handler: () -> Unit): Unit =
    js("{ try { navigator.mediaSession.setActionHandler(action, () => handler()); } catch (e) {} }")

private fun setSeekAction(action: String, handler: (Double) -> Unit): Unit =
    js("{ try { navigator.mediaSession.setActionHandler(action, (d) => handler(d.seekTime ?? d.seekOffset ?? -1)); } catch (e) {} }")

private fun setPositionState(duration: Double, rate: Double, position: Double): Unit =
    js("{ try { navigator.mediaSession.setPositionState({ duration: duration, playbackRate: rate, position: Math.min(position, duration) }); } catch (e) {} }")

private fun setPlaybackStateName(state: String): Unit = js("{ navigator.mediaSession.playbackState = state; }")

/**
 * The web's player (ADR 0022, 8) behind the same [PlaybackEngine] as Media3
 * and AVPlayer: an `<audio>` element, which plays from any host without CORS,
 * and the Media Session API for the lock screen, the notification and media
 * keys. Spike W1: a sketch — no error recovery, no stalled-stream handling.
 */
class HtmlAudioPlaybackEngine(
    private var skipBackSeconds: Int = 15,
    private var skipForwardSeconds: Int = 30,
) : PlaybackEngine {
    private val audio = document.createElement("audio") as HTMLAudioElement
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state

    init {
        audio.preload = "metadata"
        listOf("timeupdate", "durationchange", "play", "pause", "playing", "waiting", "ratechange", "ended").forEach { event ->
            audio.addEventListener(event, { publish() })
        }
        audio.addEventListener("error", { _state.update { it.copy(isPlaying = false, isBuffering = false, error = "audio error ${audio.error?.code}") } })
        if (hasMediaSession()) {
            setAction("play") { play() }
            setAction("pause") { pause() }
            setSeekAction("seekbackward") { seekTo(positionMs() - skipBackSeconds * 1000L) }
            setSeekAction("seekforward") { seekTo(positionMs() + skipForwardSeconds * 1000L) }
            setSeekAction("seekto") { seconds -> if (seconds >= 0) seekTo((seconds * 1000).toLong()) }
        }
    }

    private fun positionMs() = (audio.currentTime * 1000).toLong()

    private fun publish() {
        val duration = audio.duration.takeIf { it.isFinite() } ?: 0.0
        _state.update {
            it.copy(
                isPlaying = !audio.paused,
                isBuffering = !audio.paused && audio.readyState < 3,
                positionMs = positionMs(),
                durationMs = (duration * 1000).toLong(),
                speed = audio.playbackRate.toFloat(),
            )
        }
        if (hasMediaSession()) {
            setPlaybackStateName(if (audio.paused) "paused" else "playing")
            if (duration > 0) setPositionState(duration, audio.playbackRate, audio.currentTime)
        }
    }

    override fun load(item: PlaybackItem, playWhenReady: Boolean) {
        _state.value = PlaybackState(itemId = item.id, positionMs = item.startPositionMs, speed = _state.value.speed)
        audio.src = item.url
        audio.currentTime = item.startPositionMs / 1000.0
        if (hasMediaSession()) setMetadata(item.title, item.artist, item.artworkUrl)
        if (playWhenReady) play()
    }

    // play() returns a promise that rejects without a user gesture (autoplay policy);
    // the state then simply stays paused.
    override fun play() { audio.play() }
    override fun pause() = audio.pause()
    override fun seekTo(positionMs: Long) { audio.currentTime = positionMs.coerceAtLeast(0) / 1000.0; publish() }
    override fun setSpeed(speed: Float) { audio.playbackRate = speed.toDouble() }
    override fun release() { audio.pause(); audio.removeAttribute("src"); audio.load() }
    override fun setSkipIntervals(backSeconds: Int, forwardSeconds: Int) {
        skipBackSeconds = backSeconds; skipForwardSeconds = forwardSeconds
    }
}
