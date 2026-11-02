# Hopp in the browser: preparation and stage W2

- **Implements:** [ADR 0022](../adr/0022-web-client.md), stages W1 (done as
  spike 6) and W2; release 0.3.2 (maintainer, 2026-10-28).
- **Status:** preparation done, W2 runs locally (2026-10-28, branch `0.3.2`); the start time passed on an iPhone 12 mini; WebKit in the browser checks since 2026-11-01; VoiceOver and the menu bug in Compose's web accessibility are left.
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
- [x] **Listener API CORS** (`podcasting_tools`): merge request !25, merged
      and deployed on Nodesignal's instance (2026-10-28).

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
      First round (2026-10-29, iPhone 12 mini): 5–6 s to the first screen, in
      Safari and from the home screen — over the 4 s of ADR 0022, 11.4;
      playback and background playback work; the player's value bar was
      squeezed unreadable in Safari's short window. Done since: the cover
      gives way to the controls; a splash (the Hopp symbol, drawn by the
      browser at once) until the first frame; the local server sends Brotli
      (19.6 → 5.6 MB) and lets the browser keep the hashed files; the
      database worker no longer spends a second retrying where OPFS is
      missing; `performance.mark`s per stage (`hopp:main` … `hopp:first-frame`,
      `web/e2e/startup.mjs`). WebKit on the Mac with OPFS: 0.6 s to the first
      screen. Second round (2026-10-29, same iPhone): the welcome screen
      after 2–3 s, under the 4 s of ADR 0022, 11.4 — the start time passes;
      the app easier to use on the small screen. VoiceOver still open.
- [x] "This is not my device" at sign-in: the database copied into the
      worker's memory and its OPFS files deleted, the secrets kept in memory
      and their IndexedDB store deleted; a reload in that tab starts in
      memory again (`sessionStorage`). Hopp Web's own language choice is not
      offered there — it takes a reload. The browser's HTTP cache (feeds,
      images: public content) is the browser's to clear; a bunker session
      stays listed in the signer until ended there.
- [x] Fixes after the first run on the maintainer's machine (2026-10-28):
      the connect code as a QR code; episodes under the wrong show (the
      worker driver shared one transaction between coroutines: `SerialDriver`,
      since 2026-10-31 with transactions of its own, so a cancelled one cannot
      keep its lock — `SerialDriverTest` in `jvmTest`);
      Hopp's own language choice; "Save audio file"; a crash on a show with
      tabs (an empty tab label used twice as the pager's key).
- [x] **Its own server** (maintainer 2026-10-30: apart from Nodesignal's
      instance, which runs under systemd without containers): Debian 13, one
      core, 2 GB, 60 GB; Caddy with the headers of ADR 0022, 7 serving
      `play.` and `feeds.`; the proxy behind a socket only Caddy opens,
      systemd denying it every private address. Built: `web/deploy/`
      (build, setup, deploy), the proxy with at most eight fetches at once,
      the listener's address behind Caddy, covers kept a day; the local
      checks under the same CSP (`scripts/web-dev.sh --strict`). Set up
      the same day: an IONOS VPS, both names with Let's Encrypt
      certificates; `run.mjs --url https://play.sovereigncast.space/`
      passes as it does locally. Still `noindex`, linked nowhere.
- [x] **No wallet in the browser yet** (maintainer 2026-10-30, ADR 0022, 12):
      NWC was offered on the web; now off until W5, a connection kept before
      deleted at the next start.
- [x] Link preview (Open Graph, English) and Hopp's icon in the tab, on the
      home screen and in a web manifest (2026-10-30).
- [x] WebKit in the browser checks (2026-11-01, branch `0.3.3`):
      `node run.mjs --webkit`, each browser with a profile on disk (WebKit
      gives a throwaway context no OPFS, and Hopp ran on a database in memory
      there). All 27 checks pass, with sync 29, against
      `https://play.sovereigncast.space/`. WebKit found nothing of Hopp's:
      a screenshot's own `<style>` refused by the CSP, and loads failed by a
      reload just before the new page, are left out of the checks.
- [ ] **A menu leaves the screen reader stuck** (found 2026-10-29): two
      faults in Compose Multiplatform's web accessibility, 1.11.1 and still
      1.12.2 (`ComposeWebSemanticsListener`). It mirrors one semantics owner
      at a time: a menu or dialog becomes the one, and when it closes the
      owner is set to none instead of back to the app's — the mirror for
      screen readers freezes until the page is reloaded (a mouse or finger
      still works). And each mirrored node listens for clicks itself, so a
      click on a button inside a row bubbles up and runs the row's action
      too ("More" opened the episode). 1.13.0-beta01 (2026-10-23) fixes
      both: every owner mirrored, modal ones making the rest inert, and one
      delegated click listener acting on the event's target only. Tried
      locally the same day: it needs Kotlin 2.4.20+ and `hopp.js` loaded as
      a module, and then the app threw `NoSuchElementException` early and
      most checks failed — an upgrade of its own, once 1.13 is stable, not
      for 0.3.2. Until then `run.mjs` reloads the page after each menu or
      dialog, as a screen reader's user would have to (2026-10-31; it was
      red from its language check on — "Home" also matched "Customize
      Home", whose sheet froze the mirror; a button's exact name now goes
      first).

## Not in 0.3.2

Hosting on `play.sovereigncast.space` with own headers, imprint and privacy
policy, the signed hash of the web build, comments and boosts (W3), Umbrel
(W4), NWC in the web (W5).

## Stage W3 (0.3.3)

- [x] **WebLN** (2026-11-01, ADR 0022, 13): the browser's wallet as a second
      kind of wallet behind `WalletClient` — boosts, streaming sats, zaps
      and Sovereigncast's payments through an extension such as Alby; JVM
      tests in `ValueServiceTest`, browser checks with a fake WebLN wallet.
- [x] ~~Without WebLN: a boost as an invoice QR code~~ — dropped
      2026-11-02 (maintainer, ADR 0022, 4): most recipients are reached by
      keysend only. The wallet page names an extension, and the app.
- [ ] With a real extension (Alby) on the maintainer's machine: a boost,
      streaming sats, a zap.
