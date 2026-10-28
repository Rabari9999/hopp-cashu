# Hopp in the browser: preparation and stage W2

- **Implements:** [ADR 0022](../adr/0022-web-client.md), stages W1 (done as
  spike 6) and W2; release 0.3.2 (maintainer, 2026-10-28).
- **Status:** started 2026-10-28 on branch `0.3.2`.
- **Reference:** the spike, branch `spike/web-wasm` (local, based on
  147a7b7 before 0.3.0), report [docs/spikes/06-web.md](../spikes/06-web.md)
  once ported. Its steps are rebuilt on the current code, not rebased.

## Why

A web client reaches every iPhone through Safari while the App Store is out
of reach (ADR 0009, 0022). 0.3.2 does the groundwork in `shared` — none of
it changes the apps for the listener — and a first web client that runs
locally: sign in with a signer, sync, library, player.

## Preparation (shipped with the apps, no visible change)

- [x] **Common code compiles for the web:** `Dispatchers.IO` behind
      `platform/ioDispatcher`; secp256k1 behind `crypto/Secp256k1Ops`, the
      ACINQ library in a `nonWebMain` source set (Android, JVM, iOS).
- [x] **The `wasmJs` target only on request** (`-Phopp.web=true`): default
      builds, F-Droid's recipe and its scanner never see it.
- [x] **Crypto on the web:** `@noble/curves` through JS interop; the NIP-44,
      Schnorr, NIP-49 and BOLT 11 vectors in `commonTest`, run on the JVM and
      in Chromium. JS errors turned into Kotlin exceptions at the boundary.
- [x] **The database asynchronous:** SQLDelight `generateAsync`; every
      `executeAs…()` → `awaitAs…()`, transactions `suspend`; `jvmTest` on a
      driver that fails on a synchronous call, so a leftover is found on the
      JVM, not only in a browser.
- [x] **A wallet interface** in `shared` (`value/WalletClient`): `ValueService`,
      `BoostSender` and `WalletRepository` speak to it; NWC is its first
      implementation (WebLN in W3).
- [x] **Listener API CORS** (`podcasting_tools`): merge request !25 (branch
      `feat/listener-api-cors-2`), open for the maintainer.

## Stage W2 (local only, not published)

- [ ] Web platform: SQLite Wasm in OPFS through a worker, one tab at a time
      (Web Lock), settings, secrets in memory or wrapped by WebCrypto.
- [ ] Sign in: NIP-46 (`nostrconnect://` QR, `bunker://`) and NIP-07; never
      an nsec; "This is not my device"; sign out ends the bunker session.
- [ ] Sync with the sync key (ADR 0021, 5): library, progress, favourites,
      "Up next".
- [ ] Library, show and episode pages, the player: `<audio>` with the Media
      Session API.
- [ ] Feed proxy for local work: a container in OrbStack with the rules of
      ADR 0022, 6 (who runs it for real is open, §11.2).
- [ ] Local HTTPS for the iPhone (mkcert or Tailscale) and the two checks
      left from W1: start time on an iPhone, a VoiceOver pass.
- [ ] Browser tests with Playwright (Chromium, WebKit).

## Not in 0.3.2

Hosting on `play.sovereigncast.space` with own headers, imprint and privacy
policy, the signed hash of the web build, comments and boosts (W3), Umbrel
(W4), NWC in the web (W5).
