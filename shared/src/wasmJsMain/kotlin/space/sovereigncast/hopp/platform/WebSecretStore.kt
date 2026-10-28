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
  let chain = Promise.resolve();
  globalThis.__hoppSecrets = (name, value) => (chain = chain.then(async () => {
    if (value === null) return req('readwrite', s => s.delete(name));
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ct = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, new TextEncoder().encode(value));
    return req('readwrite', s => s.put({ iv, ct }, name));
  }));
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

    override fun get(name: String): String? {
        if (name in unreadable) throw SecretUnreadable(name)
        return values[name]
    }

    override fun put(name: String, value: String?) {
        if (value != null && name in unreadable) throw SecretUnreadable(name)
        unreadable -= name
        if (value == null) values -= name else values[name] = value
        write(name, value)
    }

    companion object {
        suspend fun open(): WebSecretStore {
            val json = openAll().await<JsString>().toString()
            val obj = Json.parseToJsonElement(json) as JsonObject
            return WebSecretStore(obj.mapValues { (it.value as? JsonPrimitive)?.takeIf { p -> p.isString }?.content })
        }
    }
}
