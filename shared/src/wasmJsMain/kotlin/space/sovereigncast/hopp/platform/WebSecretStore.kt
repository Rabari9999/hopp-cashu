package space.sovereigncast.hopp.platform

import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.js.Promise

// IndexedDB "hopp-secrets": each secret AES-GCM encrypted under a key WebCrypto made
// non-extractable — stored as a CryptoKey, its bytes never readable by any code.
private fun openAll(): Promise<JsString> = js("""(async () => {
  const db = await new Promise((res, rej) => {
    const r = indexedDB.open('hopp-secrets', 1);
    r.onupgradeneeded = () => r.result.createObjectStore('kv');
    r.onsuccess = () => res(r.result);
    r.onerror = () => rej(r.error);
  });
  const req = (mode, f) => new Promise((res, rej) => { const q = f(db.transaction('kv', mode).objectStore('kv')); q.onsuccess = () => res(q.result); q.onerror = () => rej(q.error); });
  let key = await req('readonly', s => s.get('__key'));
  if (!key) {
    key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
    await req('readwrite', s => s.put(key, '__key'));
  }
  const work = async (name, value) => {
    if (value === null) return req('readwrite', s => s.delete(name));
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ct = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, new TextEncoder().encode(value));
    return req('readwrite', s => s.put({ iv, ct }, name));
  };
  // A write that fails is tried once more, then said in the console — and the chain goes on (review
  // 2026-10-31: one rejected write left it rejected, and every later write, and the deletion of "This
  // is not my device", was skipped for the rest of the session without a word).
  let chain = Promise.resolve();
  globalThis.__hoppSecrets = (name, value) => (chain = chain.then(async () => {
    try { await work(name, value); } catch (e) {
      try { await work(name, value); } catch (again) {
        console.error('Hopp: the secret "' + name + '" was not ' + (value === null ? 'deleted' : 'stored'), again);
      }
    }
  }));
  // "This is not my device": after the writes under way, the store and its key deleted.
  globalThis.__hoppSecretsForget = () => (chain = chain.then(() => new Promise(ok => {
    db.close();
    const r = indexedDB.deleteDatabase('hopp-secrets'); r.onsuccess = r.onerror = r.onblocked = () => ok();
  })));
  const all = {};
  const names = await req('readonly', s => s.getAllKeys());
  for (const name of names) {
    if (name === '__key') continue;
    const v = await req('readonly', s => s.get(name));
    try { all[name] = new TextDecoder().decode(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: v.iv }, key, v.ct)); }
    catch (e) { all[name] = null; }
  }
  return JSON.stringify(all);
})()""")

private fun write(name: String, value: String?): Promise<JsAny?> = js("globalThis.__hoppSecrets(name, value)")
private fun forgetAll(): Promise<JsAny?> = js("globalThis.__hoppSecretsForget()")

/**
 * The browser's secret store (ADR 0022, 2): the NIP-46 client key, the sync
 * key, a signer's session — in IndexedDB, each wrapped with a WebCrypto key
 * created non-extractable. That keeps them from a copy of the browser profile,
 * not from code running in the page; the web holds no Nostr key and no wallet
 * string. Read once before the app starts ([open]); writes follow in order.
 */
class WebSecretStore private constructor(initial: Map<String, String?>) : SecretStore {
    private val values = initial.filterValues { it != null }.mapValues { it.value!! }.toMutableMap()
    private val unreadable = initial.filterValues { it == null }.keys.toMutableSet()
    private var kept = true

    override fun get(name: String): String? {
        if (name in unreadable) throw SecretUnreadable(name)
        return values[name]
    }

    override fun put(name: String, value: String?) {
        if (value != null && name in unreadable) throw SecretUnreadable(name)
        unreadable -= name
        if (value == null) values -= name else values[name] = value
        if (kept) write(name, value)
    }

    /** From here on in memory only ("This is not my device", ADR 0022, 2): what was stored is deleted. */
    suspend fun forget() {
        if (!kept) return
        kept = false
        forgetAll().await<JsAny?>()
    }

    companion object {
        suspend fun open(): WebSecretStore {
            val json = openAll().await<JsString>().toString()
            val obj = Json.parseToJsonElement(json) as JsonObject
            return WebSecretStore(obj.mapValues { (it.value as? JsonPrimitive)?.takeIf { p -> p.isString }?.content })
        }
    }
}
