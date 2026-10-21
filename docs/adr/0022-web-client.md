# ADR 0022 — Web client

- **Status:** proposed — the concept; decided by the maintainer so far:
  login by NIP-46 and NIP-07, payments by WebLN, comments and boosts in the
  web, the sync key (built 2026-10-21, ADR 0021 stage 5; checked on devices
  with Amber and a bunker the same day), the full Sovereigncast feature set,
  and the address `https://play.sovereigncast.space` — built and tested
  locally first. The rest waits for the spike (§10, stage W1).
- **Date:** 2026-10-21

## Context

An iOS release is hard for now (ADR 0009): the first iOS build goes only
through AltStore PAL, in the EU, Japan and Brazil, uploaded in a befriended
developer's account; Nodesignal has no Apple account of its own yet. A web
client reaches every iPhone through Safari, needs no App Review and no
account, and runs on any desktop.

Sync (ADR 0021, 5) makes a thin client possible: the library, progress,
favourites, "Up next" and listening habits come from the listener's relays,
encrypted. A browser holds little of its own and is clean again after
signing out.

What is different from the apps, and drives this ADR:

- **The code is served anew on every load.** The apps are signed, built
  reproducibly for F-Droid (ADR 0011), and change only by an update the
  listener takes. A web page is whatever the server sends today: whoever
  controls the host, the domain, its DNS or a script the page loads can
  send code that reads every secret the page can reach.
- **No key store.** There is no Keystore or Keychain in the browser; what
  the page can read, injected code can read.
- **One flaw is everything.** A script injected anywhere into the page
  (cross-site scripting) has the whole session, the wallet included.
- **CORS.** Most podcast feeds and image hosts send no
  `Access-Control-Allow-Origin`; a browser may not read them from another
  origin.
- **iOS Safari's limits:** a site's storage is deleted after seven days
  without a visit unless it was added to the home screen; background audio
  and timers are limited; Web Push only for home-screen apps (iOS 16.4+).

## Decision

### 1. Scope

| | Web | Note |
|---|---|---|
| Listening (streaming), chapters, speed, skip | ✅ | `<audio>` plays other hosts without CORS |
| Library, progress, favourites, "Up next", habits | ✅ through sync | without an identity: local only, see §2 |
| Search, discovery (ADR 0008) | ✅ | through the proxy (§6) |
| Comments, likes, mutes, reports (ADR 0021) | ✅ | decided 2026-10-21 |
| Boosts, also public boosts as comments | ✅ with WebLN or NWC (§4) | decided 2026-10-21 |
| Streaming sats | ✅ while the page plays | a hidden tab throttles timers; settlement per ADR 0014 |
| Sovereigncast layer (ADR 0010) | ✅ | NIP-98 through the signer |
| Downloads, offline | ❌ in the first version | would need the proxy for every audio byte |
| Push (ADR 0007) | later | Web Push, home-screen apps only on iOS |
| A key made in Hopp | ❌ | the web never holds an nsec (§2) |

### 2. Identity: only signers that keep the key

- **NIP-46** (`nostrconnect://` as a QR code for Amber, nsec.app and other
  signers on the phone; `bunker://` pasted): the way on every device, the
  only one on the iPhone.
- **NIP-07** (browser extensions such as Alby, nos2x): accepted on the
  desktop (maintainer, 2026-10-21). The key stays in the extension, out of
  the page's reach. On the iPhone there are hardly any.
- **Never an nsec in the browser:** no import, no key made here. A listener
  without Nostr makes their identity in the app, or uses a signer.
- **Without an identity** the web works as the app does: listening, the
  library in this browser only (IndexedDB), which Safari may delete after
  seven days. The page says so, and offers sync.
- **Permissions** as in the apps since 2026-10-21: standing `sign_event`
  for NIP-98, comments, likes, the mute list, reports and the relay list;
  **never a standing encryption**. Sync opens the sync key once per browser
  (ADR 0021, 5), and the signer asks the listener at that moment.
- **What the browser keeps** for a session: the NIP-46 client key (Hopp's
  own key for talking to the signer, not the listener's) and the sync key.
  Both in IndexedDB, wrapped with a WebCrypto AES-GCM key created
  non-extractable. That protects against a copy of the browser profile, not
  against code running in the page.
- **"This is not my device":** a choice at sign-in. Nothing is stored and
  everything is gone when the tab closes.
- **Signing out** sends the bunker `logout`, then deletes the client key,
  the sync key and the local library. The signer lists the session until
  then; a listener can end it there too.

### 3. The sync key in the web

The web is why the sync key exists (ADR 0021, 5, amended 2026-10-21): a
stolen NIP-46 client key cannot open the listener's messages, because the
signer never allowed decryption for good. A stolen sync key does open the
library. **Before the web is released, Hopp must be able to make a new
sync key:** on the Nostr page of any device, "End all other sessions": a
new key in a new keyring, the documents published again under it, the old
ones deleted (NIP-09, signed by the old key). Every other device then asks
its signer once more.

### 4. Payments: WebLN first, NWC later, never a wallet of its own

- **WebLN** (decided 2026-10-21) where an extension offers it (Alby and
  others): `webln.keysend` with `customRecords` for boostagrams (bLIP-10,
  ADR 0005), `webln.sendPayment` for invoices and LNURL (Lightning address,
  zaps). The extension's budget is the cap for payments it approves without
  asking; Hopp's daily budget (ADR 0005) applies on top, per browser, and
  is never synced. Not every wallet behind WebLN does keysend: a keysend
  recipient is then paid as on a wallet without keysend (ADR 0005).
- **Without WebLN:** a boost becomes an invoice shown as a QR code and a
  `lightning:` link for any wallet; no streaming sats.
- **NWC in the web**, a later stage (W5), only:
  - as a connection of its own ("Hopp Web"), never the app's, never synced;
  - made through the wallet's own flow (`nostr+walletauth`, Alby Hub's app
    connection), never pasted;
  - only `pay_invoice` and `pay_keysend` (no `list_transactions`, no
    `make_invoice`), and with a budget set in the wallet: required, not
    recommended — Hopp reads it (`get_budget`, where the wallet supports it)
    and says so where it cannot;
  - kept only in memory, or sealed with a key derived from a passkey
    (WebAuthn PRF) and opened with Face ID or Touch ID for the session.
- **Needs first:** a wallet interface in `shared`. `ValueService`,
  `BoostSender` and `WalletRepository` speak to `NwcClient` directly today;
  NWC and WebLN become two of its implementations.

### 5. Hosting

- **(A) A hosted PWA** on an origin of its own: **`https://play.sovereigncast.space`**
  (maintainer, 2026-10-21) — not a path of the website, whose scripts
  would share the origin. Static files only, no code on the server. Before
  it goes there, it is built and tested locally.
- **(B) An Umbrel app** with the web client and the proxy (§6) on the
  listener's own node, beside Alby Hub (`umbrel-app-store`). Nobody else
  serves the code or sees the feeds; from outside through Tor or Tailscale.
- **(C) nsite** (static site on Blossom, signed by Nodesignal's npub):
  later, once the integrity checks of §7 are in place.

(A) and (B) from the same build.

### 6. Feed proxy

A small service of its own, **not part of `podcasting_tools`**: a request
forgery through the proxy (SSRF) must not reach the Sovereigncast server's
database or admin.

- **Its own origin** (e.g. `feeds.hopp.sovereigncast.space`), CORS for the
  web client's origin only. Fetched content is never served from the app's
  origin: a "feed" that is HTML would run there with the session.
- **Rules:** GET only; http and https only; private, loopback and
  link-local addresses refused, after every redirect and after DNS;
  cookies and authorization headers neither sent nor returned; size limits
  (feed 20 MB, image 5 MB) and timeouts; content types allowed: XML, JSON,
  images; `X-Content-Type-Options: nosniff`; a rate limit per address.
- **No logs** beyond errors; a short shared cache.
- **What goes through it:** feeds, chapters and transcripts, value-time-split
  remote items, Podcast Index requests, and **images** — Compose on the web
  fetches covers itself (Coil over Ktor), so they need CORS too. LNURL
  servers are asked directly where they send CORS headers and through the
  proxy where not; the invoice is checked against the chosen amount as in
  the apps. **Not audio.**
- **Privacy (ADR 0015):** the proxy is a new party. Feed hosts no longer see
  the listener's IP address, but the proxy's operator could see who fetches
  which feeds. The privacy page says so; on Umbrel the party is the
  listener.
- **Who runs it:** open (§11).

### 7. Integrity of what is served

- **CSP:** `default-src 'none'`; `script-src 'self' 'wasm-unsafe-eval'`; no
  inline scripts, no third-party code, no analytics; `frame-ancestors
  'none'` (no clickjacking on the boost button); COOP `same-origin`;
  `Permissions-Policy` off for everything but the camera (QR) and
  publickey-credentials (passkey). `connect-src` must stay open to `wss:`
  (the listener's relays): **CSP does not stop data from leaving**, so the
  bundle itself has to be trustworthy.
- **Reproducible web build** in CI; its hashes published as an event signed
  by Nodesignal's npub, as for Zapstore. Anyone can rebuild and compare.
- **A service worker pins the version:** a new one is loaded only after the
  listener confirms it ("Hopp 0.4.1 is ready — what changed"), so a
  tampered server is not taken in silently on the next load.
- **Hosting:** HSTS with preload, a CAA record, two-factor login on the
  domain, DNS and hosting accounts.
- **XSS surface:** Compose renders to a canvas, so show notes, comments and
  profiles never become DOM. Whatever HTML the web adds (links, the passkey
  dialog) gets no text from feeds or relays.

### 8. Technology

- The `shared` module gets a **`wasmJs` target**; the UI is Compose
  Multiplatform's, as in the apps (ADR 0003).
- **To check in the spike (W1):**
  - `secp256k1-kmp` has no wasm target as far as known; Schnorr, ECDH and
    NIP-44 then go through JS interop with `@noble/curves` (audited), and
    the NIP-44 test vectors run against it.
  - SQLDelight on the web: the web worker driver with SQLite on OPFS.
  - The player: `HTMLAudioElement` and the Media Session API (lock screen,
    media keys).
  - Ktor's JS engine for HTTP and WebSockets.
- **Browsers:** WasmGC is needed — Chrome 119+, Firefox 120+, Safari 18.2+
  (iOS 18.2+). Older ones get a page that says so.
- **Accessibility (ADR 0016):** Compose on the web exposes less to screen
  readers than the apps; to be measured in the spike, a must before release.
- **If the spike fails** (crypto or storage not workable on wasm), the
  alternative is a TypeScript client that shares only the sync format, the
  merge and the test vectors — a second implementation to keep in step,
  so only if Compose on the web cannot be made to work.

### 9. iOS specifics

- The page invites the listener to add Hopp to the home screen: storage is
  kept, Web Push works, the app opens without Safari's bars.
- Background playback and lock-screen controls work in Safari and in a
  home-screen app (Media Session); streaming sats count only while the page
  is alive, and settle as the apps do when it comes back (ADR 0014).

### 10. Stages

- **W1 — spike:** `shared` on `wasmJs`; crypto with the NIP-44 vectors;
  storage; the player; the proxy for one feed; a first measure of
  accessibility and start time on an iPhone.
- **W2 — listening and sync:** NIP-46 and NIP-07, sync with the sync key,
  library and player; the proxy; hosting (A); "End all other sessions" (§3)
  before the release.
- **W3 — comments and boosts:** comments, likes, mutes, reports; the wallet
  interface, WebLN boosts and streaming sats; QR boosts without WebLN.
- **W4 — Umbrel:** the web client and the proxy as one Umbrel app.
- **W5 — NWC in the web**, if listeners ask for it: own connection, passkey.

### 11. Open

Gathered 2026-10-21; the order agreed with the maintainer: first 1 and 7,
the spike (W1) alongside.

Before the local build:

1. **The Listener API sends no CORS** (`listener_api.py`: "a native app has
   no browser origin"). `PODCAST_MANAGER_CORS_ORIGIN` is no way: it opens
   the whole app, the dashboard and its session cookies included. Needed:
   CORS on the `/listener` routes only, without credentials — safe because
   they authenticate with NIP-98, not cookies. Self-hosted instances need
   that version; for older ones the web reads through the proxy or shows
   no Sovereigncast layer.
2. **The proxy:** who runs it and where — proposed
   `feeds.sovereigncast.space`, a container beside Nodesignal's instance but
   apart from it; locally a container in OrbStack.
3. **Testing on the iPhone before deploying:** service workers and passkeys
   need HTTPS, `localhost` serves only the Mac. A local certificate
   (`mkcert`) or Tailscale on the LAN; Playwright (Chromium, WebKit) for the
   automated part — Maestro does not drive the web.
4. **When the spike counts as failed:** a bundle over ~10 MB, more than
   4 s to the first screen on an iPhone, a screen reader that cannot use it,
   or the crypto not passing the NIP-44 vectors.

Before `play.sovereigncast.space` goes live:

5. **Hosting with headers of our own:** GitLab Pages sets no custom
   response headers, so CSP, HSTS and COOP need a server of our own (Caddy
   or nginx). Who holds the domain and DNS, with two-factor login.
6. **Imprint and privacy policy:** a website run from Germany needs an
   imprint (§ 5 DDG); the proxy processes IP addresses, so the privacy
   policy states its legal basis, retention and server location (GDPR),
   and possibly a processing agreement with the host.
7. **"End all other sessions"** — a new sync key (§3), useful to the apps
   as well.
8. **A wallet interface in `shared`** before W3 (§4).
9. **Releasing the web:** the same version numbers as the apps; who signs
   the hash event (as for Zapstore, through the Amber bunker); the
   reproducible build on the Mac runner, not on CI minutes.

To decide along the way:

10. **The name** on `play.sovereigncast.space`: Hopp (ADR 0004)?
11. **Deep links:** `sovereigncast.space/open` could offer "Listen in the
    browser" beside the stores.
12. **The domain's neighbours:** `play.` shares the site with the website;
    unproblematic as long as nothing there sets cookies for the whole
    domain. Nodesignal's instance is on `pm.nodesignal.space`.

Earlier:

- ~~Who runs the proxy, and on which domain the web client lives~~ — the
  domain is decided (§5); the proxy is point 2 above.
- ~~Whether the web carries the full Sovereigncast feature set~~ — decided
  2026-10-21: the full set, as the `altstore` build (ADR 0009); App Review
  does not apply on the web.
- Whether a home-screen app on iOS should get Web Push for new episodes
  (needs a push service, ADR 0007).

## Consequences

- **Distribution (ADR 0011)** gains the web as a channel: a reproducible
  bundle in CI, its hashes signed by Nodesignal, and for (B) an Umbrel app.
- **Server work:** the proxy as a service of its own, with its tests (SSRF
  rules first); its operation and monitoring.
- **Privacy (ADR 0015):** the proxy as a party; the page explains what
  stays in the browser and how to remove it.
- **Payments (ADR 0005):** a wallet interface in `shared` before W3; the
  daily budget per browser.
- **Sync (ADR 0021, 5):** "End all other sessions" — a new sync key — is a
  must before W2 ships, and helps the apps too (a lost phone).
- **iOS (ADR 0009):** the web takes pressure off the App Store, but does not
  replace a native app: no downloads, weaker background playback, no
  CarPlay. The `altstore` and `appstore` plans stay.
