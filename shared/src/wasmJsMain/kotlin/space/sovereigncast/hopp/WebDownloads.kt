package space.sovereigncast.hopp

import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import kotlinx.io.files.Path
import space.sovereigncast.hopp.data.BrowserDownloads
import space.sovereigncast.hopp.data.DownloadFiles
import space.sovereigncast.hopp.data.DownloadRefused
import kotlin.js.Promise

/*
 * Episodes kept in the browser for offline listening (maintainer 2026-11-08, as Squirrel does; ADR 0022, 1):
 * the audio fetched straight from the podcast's server — never through the feed proxy, which takes feeds
 * and is no road for every audio byte — and kept in IndexedDB in pieces of a few megabytes, so no episode
 * is held in memory whole. A server that does not let a web page read its audio (no CORS on it or on a
 * redirect before it) refuses; the episode menu then offers the audio file to save instead.
 */

// The helpers, once per page: open the store, take a transaction to its end, the keys of an episode's pieces.
private fun helpers(): Unit = js("""{
  if (globalThis.hoppDl) return;
  const dl = {
    open: () => new Promise((ok, no) => {
      const r = indexedDB.open('hopp-downloads', 1);
      r.onupgradeneeded = () => { r.result.createObjectStore('chunks'); r.result.createObjectStore('files'); };
      r.onsuccess = () => ok(r.result);
      r.onerror = () => no(r.error);
    }),
    tx: (db, stores, mode, fn) => new Promise((ok, no) => {
      const t = db.transaction(stores, mode);
      t.oncomplete = () => ok();
      t.onerror = t.onabort = () => no(t.error);
      fn(t);
    }),
    key: (id, n) => id + ':' + String(n).padStart(6, '0'),
    range: (id) => IDBKeyRange.bound(id + ':', id + ':￿'),
    drop: async (id) => {
      const db = await dl.open();
      try {
        await dl.tx(db, ['chunks', 'files'], 'readwrite', t => {
          t.objectStore('chunks').delete(dl.range(id));
          t.objectStore('files').delete(String(id));
        });
      } finally { db.close(); }
    },
    blob: async (id) => {
      const db = await dl.open();
      let meta = null, parts = null;
      try {
        await dl.tx(db, ['chunks', 'files'], 'readonly', t => {
          const m = t.objectStore('files').get(String(id));
          const c = t.objectStore('chunks').getAll(dl.range(id));
          m.onsuccess = () => { meta = m.result; };
          c.onsuccess = () => { parts = c.result; };
        });
      } finally { db.close(); }
      if (!meta || !parts || parts.length !== meta.chunks) return null;
      return new Blob(parts, { type: meta.type || 'audio/mpeg' });
    },
  };
  globalThis.hoppDl = dl;
}""")

/** "ok:<bytes>", "refused:<why>" (for good), "retry:<why>" (the network), or "stopped". */
private fun fetchInto(id: Double, url: String, cap: Double, progress: (Double, Double) -> Unit, stopped: () -> Boolean): Promise<JsString> = js("""(async () => {
 try {
  const dl = globalThis.hoppDl;
  // A tab that keeps nothing keeps no episode either (ADR 0022, 2).
  try { if (sessionStorage.getItem('hopp-tab-only') === '1') return 'refused:this tab keeps nothing'; } catch (e) {}
  const ctl = new AbortController();
  let res;
  try {
    res = await fetch(url, { signal: ctl.signal, credentials: 'omit', referrerPolicy: 'no-referrer', cache: 'no-store' });
  } catch (e) {
    // A browser says "failed" alike for no network and for a server that does not allow the page (CORS).
    return navigator.onLine === false ? 'retry:offline' : 'refused:the server does not let a web page download it';
  }
  if (!res.ok || !res.body) {
    const s = res.status;
    return (s >= 400 && s < 500 && s !== 408 && s !== 429) ? 'refused:HTTP ' + s : 'retry:HTTP ' + s;
  }
  const type = (res.headers.get('content-type') || '').split(';')[0].trim().toLowerCase();
  // A web page is no episode — a tracker's error page, a login wall (security review 2026-10-10, M-03).
  if (type.startsWith('text/') || type === 'application/json' || type === 'application/xml' || type === 'application/xhtml+xml') {
    ctl.abort(); return 'refused:not an episode: ' + type;
  }
  const total = Number(res.headers.get('content-length')) || 0;
  if (total > cap) { ctl.abort(); return 'refused:too large'; }
  // Asked once: a browser may then keep it when space runs short.
  try { await navigator.storage?.persist?.(); } catch (e) {}
  await dl.drop(id);
  const db = await dl.open();
  const reader = res.body.getReader();
  let written = 0, n = 0, buffer = [], buffered = 0, reported = 0;
  const flush = async () => {
    if (!buffered) return;
    const piece = new Blob(buffer);
    buffer = []; buffered = 0;
    await dl.tx(db, ['chunks'], 'readwrite', t => t.objectStore('chunks').put(piece, dl.key(id, n)));
    n++;
  };
  try {
    for (;;) {
      if (stopped()) { ctl.abort(); db.close(); await dl.drop(id); return 'stopped'; }
      const { done, value } = await reader.read();
      if (done) break;
      written += value.length;
      if (written > cap) { ctl.abort(); db.close(); await dl.drop(id); return 'refused:too large'; }
      buffer.push(value); buffered += value.length;
      if (buffered >= 4 * 1024 * 1024) await flush();
      if (written - reported >= 256 * 1024) { reported = written; progress(written, total); }
    }
    await flush();
    if (total && written < total) { db.close(); await dl.drop(id); return 'retry:incomplete'; }
    await dl.tx(db, ['files'], 'readwrite', t => t.objectStore('files').put({ chunks: n, type: type || 'audio/mpeg', bytes: written }, String(id)));
    db.close();
    return 'ok:' + written;
  } catch (e) {
    try { ctl.abort(); } catch (x) {}
    try { db.close(); } catch (x) {}
    try { await dl.drop(id); } catch (x) {}
    // Space used up is no network problem: asked again it would only fail again.
    if (e && e.name === 'QuotaExceededError') return 'refused:no space left in the browser';
    return 'retry:' + (e && e.message ? e.message : 'network');
  }
 } catch (e) {
  // Whatever else went wrong — the browser's storage among it — said, not thrown past the download.
  console.warn('Hopp download', e);
  return 'retry:' + (e && e.name ? e.name + ': ' : '') + (e && e.message ? e.message : String(e));
 }
})()""")

private fun dropStored(id: Double): Promise<JsAny?> = js("globalThis.hoppDl.drop(id)")

/** Every episode kept, gone: signing out, a tab that keeps nothing, "Delete all downloads". */
internal fun clearStoredEpisodes(): Promise<JsAny?> = js("""new Promise(ok => {
  try {
    const r = indexedDB.deleteDatabase('hopp-downloads');
    r.onsuccess = r.onerror = r.onblocked = () => ok(null);
  } catch (e) { ok(null); }
})""")

/** The episode kept for [id] as an address the audio element plays, or "" when the browser no longer has it. */
private fun storedAddress(id: Double, done: (String) -> Unit): Unit = js("""{
  globalThis.hoppDl.blob(id).then(b => done(b ? URL.createObjectURL(b) : ''), () => done(''));
}""")

internal fun playStored(id: Long, done: (String) -> Unit) { helpers(); storedAddress(id.toDouble(), done) }

internal class WebDownloads : BrowserDownloads {
    override suspend fun fetch(id: Long, url: String, maxBytes: Long, progress: (written: Long, total: Long?) -> Unit, stopped: () -> Boolean): Long {
        helpers()
        val answer = try {
            fetchInto(id.toDouble(), url, maxBytes.toDouble(), { written, total ->
                progress(written.toLong(), total.toLong().takeIf { it > 0 })
            }, stopped).await<JsString>().toString()
        } catch (e: Throwable) {
            // A JavaScript error is no Exception in Kotlin/Wasm: one the repository can take.
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw IOException(e.message ?: "download failed", e)
        }
        return when {
            answer.startsWith("ok:") -> answer.removePrefix("ok:").toLong()
            answer.startsWith("refused:") -> throw DownloadRefused(0, answer.removePrefix("refused:"))
            answer == "stopped" -> throw IllegalStateException("stopped")
            else -> throw IOException(answer.removePrefix("retry:"))
        }
    }

    override suspend fun delete(id: Long) {
        helpers()
        runCatching { dropStored(id.toDouble()).await<JsAny?>() }
    }
}

/** In the browser there is no download folder: nothing to make, and what goes goes from the store. */
internal class WebDownloadFiles : DownloadFiles("") {
    private val scope = kotlinx.coroutines.MainScope()
    override fun size(path: Path): Long = 0L
    override fun exists(path: String) = false
    override fun delete(path: String) = Unit
    override fun ensureDir() = Unit
    override fun clearAll() { scope.launch { clearStoredEpisodes().await<JsAny?>() } }
}
