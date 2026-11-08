package space.sovereigncast.hopp.playback

import kotlinx.browser.document
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.w3c.dom.HTMLAudioElement
import space.sovereigncast.hopp.data.BrowserDownloads
import space.sovereigncast.hopp.platform.PlaybackEngine
import space.sovereigncast.hopp.platform.PlaybackItem
import space.sovereigncast.hopp.platform.PlaybackState

private fun hasMediaSession(): Boolean = js("'mediaSession' in navigator")
private fun revokeAddress(address: String): Unit = js("{ try { URL.revokeObjectURL(address); } catch (e) {} }")
// A refused play() — no gesture yet, no source (a stream with the network gone) — leaves the state paused; the
// promise's rejection is no error of the page's.
private fun playAudio(audio: HTMLAudioElement): Unit = js("{ const p = audio.play(); if (p && p.catch) p.catch(() => {}); }")

private fun setMetadata(title: String, artist: String, artwork: String?): Unit =
    js("{ navigator.mediaSession.metadata = new MediaMetadata({ title: title, artist: artist, artwork: artwork ? [{ src: artwork }] : [] }); }")

private fun setAction(action: String, handler: () -> Unit): Unit =
    js("{ try { navigator.mediaSession.setActionHandler(action, () => handler()); } catch (e) {} }")

private fun setSeekAction(action: String, handler: (Double) -> Unit): Unit =
    js("{ try { navigator.mediaSession.setActionHandler(action, (d) => handler(d.seekTime ?? d.seekOffset ?? -1)); } catch (e) {} }")

private fun setPositionState(duration: Double, rate: Double, position: Double): Unit =
    js("{ try { navigator.mediaSession.setPositionState({ duration: duration, playbackRate: rate, position: Math.min(position, duration) }); } catch (e) {} }")

private fun setPlaybackStateName(state: String): Unit = js("{ navigator.mediaSession.playbackState = state; }")

private fun clearMediaSession(): Unit = js("{ try { navigator.mediaSession.metadata = null; navigator.mediaSession.playbackState = 'none'; } catch (e) {} }")

/** The volume the listener left, kept in this browser only; full when none or unreadable. */
private fun storedVolume(): Double = js("(() => { try { const v = parseFloat(localStorage.getItem('hopp-volume')); return isNaN(v) ? 1 : Math.min(1, Math.max(0, v)); } catch (e) { return 1; } })()")

private fun storeVolume(level: Double): Unit = js("{ try { localStorage.setItem('hopp-volume', String(level)); } catch (e) {} }")

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
    // Safari on iOS keeps an audio element at full volume and ignores what is set: no slider there.
    private val ownVolume = run { audio.volume = 0.5; val takes = audio.volume == 0.5; audio.volume = 1.0; takes }
    private val _volume = MutableStateFlow(storedVolume().toFloat())
    override val volume: StateFlow<Float>? = if (ownVolume) _volume else null

    override fun setVolume(level: Float) {
        if (!ownVolume) return
        val v = level.coerceIn(0f, 1f)
        audio.volume = v.toDouble()
        _volume.value = v
        storeVolume(v.toDouble())
    }

    /** The speed asked for. A new source puts the element back to its default rate, and Safari drops
     *  one set before the file's metadata is in (maintainer 2026-10-31: every show played at 1×): it
     *  is the default too, and set again once the file can play. */
    private var speed = 1.0

    private fun keepSpeed() { if (kotlin.math.abs(audio.playbackRate - speed) > 0.001) audio.playbackRate = speed }

    init {
        if (ownVolume) audio.volume = _volume.value.toDouble()
        audio.preload = "metadata"
        listOf("loadedmetadata", "canplay", "play").forEach { event -> audio.addEventListener(event, { keepSpeed() }) }
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

    // An episode kept in the browser plays from an address made for it, given back when another one loads.
    private var stored: String? = null
    private var loads = 0

    override fun load(item: PlaybackItem, playWhenReady: Boolean) {
        _state.value = PlaybackState(itemId = item.id, positionMs = item.startPositionMs, speed = _state.value.speed)
        val load = ++loads
        stored?.let(::revokeAddress); stored = null
        val kept = item.url.removePrefix(BrowserDownloads.PATH_PREFIX).takeIf { item.url.startsWith(BrowserDownloads.PATH_PREFIX) }
        if (kept == null) return start(item, item.url, playWhenReady)
        // From the browser's storage (WebDownloads.kt) — the stream after the # when the browser let it go.
        val id = kept.substringBefore('#').toLongOrNull()
        val stream = kept.substringAfter('#', "")
        if (id == null) return start(item, stream, playWhenReady)
        space.sovereigncast.hopp.playStored(id) { address ->
            // Another episode asked for meanwhile: this one's address is not needed.
            if (load != loads) { if (address.isNotEmpty()) revokeAddress(address); return@playStored }
            stored = address.ifEmpty { null }
            start(item, address.ifEmpty { stream }, playWhenReady)
        }
    }

    private fun start(item: PlaybackItem, url: String, playWhenReady: Boolean) {
        audio.src = url
        audio.currentTime = item.startPositionMs / 1000.0
        if (hasMediaSession()) setMetadata(item.title, item.artist, item.artworkUrl)
        if (playWhenReady) play()
    }

    // play() returns a promise that rejects without a user gesture (autoplay policy);
    // the state then simply stays paused.
    override fun play() = playAudio(audio)
    override fun pause() = audio.pause()
    override fun seekTo(positionMs: Long) { audio.currentTime = positionMs.coerceAtLeast(0) / 1000.0; publish() }
    override fun setSpeed(speed: Float) {
        this.speed = speed.toDouble()
        audio.defaultPlaybackRate = this.speed
        audio.playbackRate = this.speed
    }
    override fun unload() {
        loads++
        stored?.let(::revokeAddress); stored = null
        _state.value = PlaybackState(speed = speed.toFloat())
        audio.pause(); audio.removeAttribute("src"); audio.load()
        _state.value = PlaybackState(speed = speed.toFloat())
        if (hasMediaSession()) clearMediaSession()
    }
    override fun release() { audio.pause(); audio.removeAttribute("src"); audio.load() }
    override fun setSkipIntervals(backSeconds: Int, forwardSeconds: Int) {
        skipBackSeconds = backSeconds; skipForwardSeconds = forwardSeconds
    }
}
