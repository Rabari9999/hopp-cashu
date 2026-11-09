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

private fun hasWebAudio(): Boolean = js("!!(window.AudioContext || window.webkitAudioContext)")

/** The host of [url]; empty where it has none (a blob: address). */
private fun hostOf(url: String): String = js("(() => { try { const u = new URL(url, location.href); return u.protocol === 'blob:' ? '' : u.host; } catch (e) { return ''; } })()")

private fun sameOrigin(url: String): Boolean =
    js("(() => { try { const u = new URL(url, location.href); return u.protocol === 'blob:' || u.origin === location.origin; } catch (e) { return false; } })()")

/**
 * Whether the server of [url] lets this page work with its audio (CORS): a HEAD request, which
 * downloads nothing. It answering at all, in "cors" mode, says it does; refused, it throws.
 */
private fun probeCors(url: String, done: (Boolean) -> Unit): Unit = js("""{
  fetch(url, { method: 'HEAD', mode: 'cors', credentials: 'omit', cache: 'no-store', redirect: 'follow' })
    .then(r => done(r.status < 400 || r.status === 405)).catch(() => done(false));
}""")

/**
 * The browser's audio processing behind [audio] (Web Audio): a compressor whose make-up gain makes
 * quiet speech louder and keeps the peaks below full scale (the boost), and a meter on the sound as it
 * comes, which says when it has been quiet for a while ([onQuiet]) — the page then plays faster until
 * it is not. Both pass the sound unchanged while off.
 */
private fun makeGraph(audio: HTMLAudioElement, onQuiet: (Boolean) -> Unit): JsAny = js("""(() => {
  const Ctx = window.AudioContext || window.webkitAudioContext;
  const ctx = new Ctx();
  const source = ctx.createMediaElementSource(audio);
  const comp = ctx.createDynamicsCompressor();
  const analyser = ctx.createAnalyser();
  analyser.fftSize = 2048;
  source.connect(comp); comp.connect(ctx.destination); source.connect(analyser);
  const g = { ctx: ctx, silence: false, quiet: false, quietSince: -1, buf: new Float32Array(analyser.fftSize) };
  g.boost = (on) => {
    comp.threshold.value = on ? -30 : 0; comp.knee.value = on ? 10 : 0; comp.ratio.value = on ? 3 : 1;
    comp.attack.value = 0.005; comp.release.value = 0.25;
  };
  g.boost(false);
  const set = (q) => { if (g.quiet !== q) { g.quiet = q; onQuiet(q); } };
  g.timer = setInterval(() => {
    if (!g.silence || audio.paused) { g.quietSince = -1; set(false); return; }
    analyser.getFloatTimeDomainData(g.buf);
    let sum = 0;
    for (let i = 0; i < g.buf.length; i++) sum += g.buf[i] * g.buf[i];
    const now = performance.now();
    if (Math.sqrt(sum / g.buf.length) < 0.008) {
      if (g.quietSince < 0) g.quietSince = now;
      if (now - g.quietSince > 350) set(true);
    } else { g.quietSince = -1; set(false); }
  }, 40);
  return g;
})()""")

private fun graphBoost(g: JsAny, on: Boolean): Unit = js("g.boost(on)")
private fun graphSilence(g: JsAny, on: Boolean): Unit = js("{ g.silence = on; }")
// A gesture lets a context that started suspended (autoplay policy) run.
private fun graphResume(g: JsAny): Unit = js("{ if (g.ctx.state !== 'running') g.ctx.resume().catch(() => {}); }")
private fun graphClose(g: JsAny): Unit = js("{ clearInterval(g.timer); g.ctx.close().catch(() => {}); }")

/**
 * The web's player (ADR 0022, 8) behind the same [PlaybackEngine] as Media3
 * and AVPlayer: an `<audio>` element, which plays from any host without CORS,
 * and the Media Session API for the lock screen, the notification and media
 * keys. Spike W1: a sketch — no error recovery, no stalled-stream handling.
 *
 * The volume boost and skipping silence (maintainer 2026-11-09) need the browser's audio
 * processing, which only gets sound from a server that allows the page to read it (CORS):
 * from any other it gets silence. So an episode goes through it only where its server was
 * asked first and allows it, or it is kept in the browser; elsewhere it plays as before and
 * [effectsRefused] says so. One element for each way: an element once given to the processing
 * stays with it.
 */
class HtmlAudioPlaybackEngine(
    private var skipBackSeconds: Int = 15,
    private var skipForwardSeconds: Int = 30,
) : PlaybackEngine {
    private var audio = document.createElement("audio") as HTMLAudioElement
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state
    // Safari on iOS keeps an audio element at full volume and ignores what is set: no slider there.
    private val ownVolume = run { audio.volume = 0.5; val takes = audio.volume == 0.5; audio.volume = 1.0; takes }
    private val _volume = MutableStateFlow(storedVolume().toFloat())
    override val volume: StateFlow<Float>? = if (ownVolume) _volume else null

    // Not on iOS (the same test as the volume): Safari stops processed audio there when the screen locks.
    private val canProcess = ownVolume && hasWebAudio()
    override val supportsSkipSilence: Boolean get() = canProcess
    override val supportsVolumeBoost: Boolean get() = canProcess
    private val _refused = MutableStateFlow(false)
    override val effectsRefused: StateFlow<Boolean>? = if (canProcess) _refused else null

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

    private var boost = false
    private var skipSilence = false
    /** The processing behind [audio], if it is the processed element. */
    private var graph: JsAny? = null
    /** Quiet for a while now, while skipping silence: played faster. */
    private var quiet = false
    /** Hosts asked whether they allow the processing, and their answers. */
    private val allows = mutableMapOf<String, Boolean>()
    private var current: PlaybackItem? = null
    private var currentUrl: String? = null

    private fun rate() = if (quiet) maxOf(speed, minOf(speed * SILENCE_FACTOR, SILENCE_MAX)) else speed
    private fun keepSpeed() { if (kotlin.math.abs(audio.playbackRate - rate()) > 0.001) audio.playbackRate = rate() }

    /** Elements made so far: only the newest one's events count. Before [init], which makes the first. */
    private var elements = 0

    init {
        listen(audio)
        if (hasMediaSession()) {
            setAction("play") { play() }
            setAction("pause") { pause() }
            setSeekAction("seekbackward") { seekTo(positionMs() - skipBackSeconds * 1000L) }
            setSeekAction("seekforward") { seekTo(positionMs() + skipForwardSeconds * 1000L) }
            setSeekAction("seekto") { seconds -> if (seconds >= 0) seekTo((seconds * 1000).toLong()) }
        }
    }

    /** What every element of this player gets: its volume, its rate kept, its events reported — only while it is the one. */
    private fun listen(element: HTMLAudioElement) {
        val mine = ++elements
        if (ownVolume) element.volume = _volume.value.toDouble()
        element.preload = "metadata"
        listOf("loadedmetadata", "canplay", "play").forEach { event -> element.addEventListener(event, { if (mine == elements) keepSpeed() }) }
        listOf("timeupdate", "durationchange", "play", "pause", "playing", "waiting", "ratechange", "ended").forEach { event ->
            element.addEventListener(event, { if (mine == elements) publish() })
        }
        element.addEventListener("error", { if (mine == elements) failed() })
    }

    private fun failed() {
        val url = currentUrl
        val item = current
        // Allowed when asked, and refused all the same (a redirect to another host): the episode as before, from where it was.
        if (graph != null && url != null && item != null && !sameOrigin(url)) {
            allows[hostOf(url)] = false
            _refused.value = true
            return start(item.copy(startPositionMs = positionMs()), url, playWhenReady = !audio.paused || _state.value.isPlaying)
        }
        _state.update { it.copy(isPlaying = false, isBuffering = false, error = "audio error ${audio.error?.code}") }
    }

    /** [audio] the processed element or a plain one; the one before emptied. */
    private fun use(processed: Boolean) {
        if ((graph != null) == processed) return
        val before = audio
        before.pause(); before.removeAttribute("src"); before.load()
        graph?.let(::graphClose); graph = null
        quiet = false
        audio = document.createElement("audio") as HTMLAudioElement
        if (processed) audio.crossOrigin = "anonymous"
        listen(audio)
        if (processed) graph = runCatching {
            makeGraph(audio) { q -> quiet = q; keepSpeed() }
        }.getOrNull()?.also { g -> graphBoost(g, boost); graphSilence(g, skipSilence) }
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
                // The speed asked for, not the faster one over a silence.
                speed = speed.toFloat(),
            )
        }
        if (hasMediaSession()) {
            setPlaybackStateName(if (audio.paused) "paused" else "playing")
            if (duration > 0) setPositionState(duration, speed, audio.currentTime)
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

    /** Through the processing where it is wanted and the server allows it — asked once per host. */
    private fun start(item: PlaybackItem, url: String, playWhenReady: Boolean) {
        current = item; currentUrl = url
        val load = loads
        if (!canProcess || !(boost || skipSilence)) { _refused.value = false; return play(item, url, playWhenReady, processed = graph != null && sameOrigin(url)) }
        if (sameOrigin(url)) { _refused.value = false; return play(item, url, playWhenReady, processed = true) }
        val host = hostOf(url)
        allows[host]?.let { ok -> _refused.value = !ok; return play(item, url, playWhenReady, processed = ok) }
        probeCors(url) { ok ->
            allows[host] = ok
            if (load != loads || currentUrl != url) return@probeCors
            _refused.value = !ok
            play(item, url, playWhenReady, processed = ok)
        }
    }

    private fun play(item: PlaybackItem, url: String, playWhenReady: Boolean, processed: Boolean) {
        use(processed)
        audio.defaultPlaybackRate = speed
        audio.src = url
        audio.currentTime = item.startPositionMs / 1000.0
        if (hasMediaSession()) setMetadata(item.title, item.artist, item.artworkUrl)
        if (playWhenReady) play()
    }

    // play() returns a promise that rejects without a user gesture (autoplay policy);
    // the state then simply stays paused.
    override fun play() { graph?.let(::graphResume); playAudio(audio) }
    override fun pause() = audio.pause()
    override fun seekTo(positionMs: Long) { audio.currentTime = positionMs.coerceAtLeast(0) / 1000.0; publish() }
    override fun setSpeed(speed: Float) {
        this.speed = speed.toDouble()
        audio.defaultPlaybackRate = this.speed
        audio.playbackRate = rate()
    }
    override fun unload() {
        loads++
        current = null; currentUrl = null
        stored?.let(::revokeAddress); stored = null
        _state.value = PlaybackState(speed = speed.toFloat())
        audio.pause(); audio.removeAttribute("src"); audio.load()
        _state.value = PlaybackState(speed = speed.toFloat())
        _refused.value = false
        if (hasMediaSession()) clearMediaSession()
    }
    override fun release() { audio.pause(); audio.removeAttribute("src"); audio.load(); graph?.let(::graphClose); graph = null }
    override fun setSkipIntervals(backSeconds: Int, forwardSeconds: Int) {
        skipBackSeconds = backSeconds; skipForwardSeconds = forwardSeconds
    }

    /** The settings, live: an episode playing without the processing it now needs is loaded again into it, where it was. */
    override fun setAudioEffects(volumeBoost: Boolean, skipSilence: Boolean) {
        if (!canProcess || (volumeBoost == boost && skipSilence == this.skipSilence)) return
        boost = volumeBoost; this.skipSilence = skipSilence
        graph?.let { g -> graphBoost(g, boost); graphSilence(g, skipSilence) }
        if (!skipSilence && quiet) { quiet = false; keepSpeed() }
        val item = current ?: return
        val url = currentUrl ?: return
        if ((boost || skipSilence) && graph == null) start(item.copy(startPositionMs = positionMs()), url, playWhenReady = !audio.paused)
        else if (!(boost || skipSilence)) _refused.value = false
    }

    private companion object {
        /** Over a silence: this much faster, at most [SILENCE_MAX] — browsers mute beyond 4× or so. */
        const val SILENCE_FACTOR = 3.0
        const val SILENCE_MAX = 4.0
    }
}
