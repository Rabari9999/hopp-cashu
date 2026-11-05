// Hopp Web's service worker (ADR 0022, 14). It keeps one build of Hopp — every file of
// release.json, each checked against its SHA-256 there — and serves the page from it: Hopp starts
// fast and without a network, and a new build takes over only when the listener says so ("Hopp
// 0.4.0 is ready — reload"), never in the middle of listening, never half of one build and half
// of another.
//
// What it is not: protection against the server. The browser fetches this file from the server
// to look for updates, so whoever controls the server controls the next one too. That check is
// done from outside (web/deploy/verify.mjs against Nodesignal's signature).
//
// The version is put in by web/deploy/release.mjs: the browser sees an update only when this file
// changes.
const VERSION = '__HOPP_VERSION__';
const CACHE = 'hopp-' + VERSION;
const LIST = 'release.json';

const hex = (buf) => Array.from(new Uint8Array(buf), (b) => b.toString(16).padStart(2, '0')).join('');
const sha256 = async (buf) => hex(await crypto.subtle.digest('SHA-256', buf));
const scope = () => new URL(self.registration.scope).pathname;
// Content-hashed by webpack: the same name is the same file, kept from the build before.
const hashedName = /^[0-9a-f]{20}\.(wasm|js)$/;
// The server's headers go with each file — the page's CSP and the rest of ADR 0022, 7 among them;
// not those of a body the browser has already unpacked.
const kept = (headers) => {
  const out = new Headers(headers);
  for (const h of ['Content-Encoding', 'Content-Length', 'Transfer-Encoding']) out.delete(h);
  return out;
};

self.addEventListener('install', (event) => event.waitUntil((async () => {
  const listed = await fetch(LIST, { cache: 'no-cache' });
  if (!listed.ok) throw new Error('no build list');
  const listBytes = await listed.arrayBuffer();
  const list = JSON.parse(new TextDecoder().decode(listBytes));
  await caches.delete(CACHE);
  const cache = await caches.open(CACHE);
  for (const [path, want] of Object.entries(list.files)) {
    // A file the build before had already, unchanged: taken from there, not fetched again.
    const before = hashedName.test(path) ? await caches.match(path) : undefined;
    const response = before ?? await fetch(path, { cache: 'no-cache' });
    if (!response.ok) throw new Error(`${path}: HTTP ${response.status}`);
    const body = await response.arrayBuffer();
    // One file that is not what the list says, and this build is not taken at all.
    if (await sha256(body) !== want) throw new Error(`${path} is not the file of the build list`);
    await cache.put(path, new Response(body, { headers: kept(response.headers) }));
  }
  await cache.put(LIST, new Response(listBytes, { headers: kept(listed.headers) }));
  // The first build takes the page at once; a later one waits for the listener.
  if (!self.registration.active) await self.skipWaiting();
  else for (const client of await self.clients.matchAll()) client.postMessage({ type: 'hopp:ready', version: VERSION });
})()));

self.addEventListener('activate', (event) => event.waitUntil((async () => {
  for (const name of await caches.keys()) if (name.startsWith('hopp-') && name !== CACHE) await caches.delete(name);
  await self.clients.claim();
})()));

self.addEventListener('message', (event) => {
  if (event.data === 'hopp:update') self.skipWaiting();
  // A page that opened while this build was waiting asks it what it is.
  if (event.data === 'hopp:version') event.source?.postMessage({ type: 'hopp:ready', version: VERSION });
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin || url.search || !url.pathname.startsWith(scope())) return;
  const path = url.pathname.slice(scope().length) || 'index.html';
  event.respondWith((async () => (await (await caches.open(CACHE)).match(path)) ?? fetch(request))());
});
