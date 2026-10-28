# Hopp in the browser: preparation and stage W2

- **Implements:** [ADR 0022](../adr/0022-web-client.md), stages W1 (done as
  spike 6) and W2; release 0.3.2 (maintainer, 2026-10-28).
- **Status:** preparation done, W2 runs locally (2026-10-28, branch `0.3.2`); the iPhone checks are left.
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

How to run and check it: [web/README.md](../../web/README.md).

- [x] Web platform: SQLite Wasm in OPFS through a worker, created or migrated
      by `user_version` before the app starts; one tab at a time (Web Lock);
      secrets in IndexedDB under a non-extractable WebCrypto key.
- [x] Sign in: NIP-07 (`Nip07Signer`, as an external signer), and the
      existing nostrconnect and bunker; no key made or imported in the
      browser (`Platform.keysInApp`); signing out ends the bunker session
      and deletes all Hopp kept in the browser.
- [x] Sync with the sync key: two browsers, one key — the subscription
      restored, the signer asked to decrypt once.
- [x] Library, show and episode pages, the player (`<audio>`, Media
      Session); no downloads offered; fallback fonts for symbols and emoji.
- [x] Feed proxy for local work (`web/proxy`, Go, in Docker), with the rules
      of ADR 0022, 6 and tests; the browser sends every GET through it but
      the Podcast Index and the Listener API.
- [x] Local HTTPS for the iPhone (`scripts/web-dev.sh --lan`, mkcert).
- [x] Browser checks with Playwright (`web/e2e/run.mjs`, Chromium).
- [ ] **The two checks left from W1, on a real iPhone** (maintainer): start
      time in Safari and from the home screen, first visit and from cache;
      a VoiceOver pass over the tour and one list.
- [ ] "This is not my device" at sign-in: nothing kept beyond the tab — the
      database in memory as well, not only the secrets.
- [ ] The Listener API's CORS deployed (podcasting_tools !25); until then a
      Sovereigncast show has no community tabs in the browser.
- [ ] WebKit in the browser checks (Playwright's WebKit build).

## Not in 0.3.2

Hosting on `play.sovereigncast.space` with own headers, imprint and privacy
policy, the signed hash of the web build, comments and boosts (W3), Umbrel
(W4), NWC in the web (W5).
