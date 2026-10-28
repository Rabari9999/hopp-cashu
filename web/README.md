# Hopp in the browser

The web client of [ADR 0022](../docs/adr/0022-web-client.md): the same
Compose app as on the phones, built for `wasmJs` from `shared`, plus the
feed proxy in [`proxy/`](proxy/). Stage W2 — it runs locally and is not
published anywhere yet. What is done and what is open:
[docs/tasks/web-client.md](../docs/tasks/web-client.md).

The `wasmJs` target exists only with `-Phopp.web=true`: the apps' builds,
F-Droid's recipe and its scanner never see it, nor Node, Yarn or npm.

## Run it

Needs what the apps need (JDK 21, the Android SDK) and Docker (OrbStack)
for the proxy.

```bash
scripts/web-dev.sh             # build, start the proxy, serve on http://127.0.0.1:8642/
scripts/web-dev.sh --no-build  # serve what was built last
```

| Part | Where |
|---|---|
| The client | `shared/src/wasmJsMain` — `Main.kt` opens the database (SQLite Wasm in a worker, the file in OPFS), then starts the app as the phones do |
| The page | `shared/src/wasmJsMain/resources/index.html`; the proxy's address is its `hopp-feed-proxy` meta tag |
| The proxy | `web/proxy` — Go, standard library only; its tests: `docker run --rm -v "$PWD/web/proxy":/src -w /src golang:1.25-alpine go test ./...` |
| Fallback fonts | `shared/src/wasmJsMain/resources/fonts` (symbols, emoji; see its README) |

What differs from the apps, by decision (ADR 0022):

- **No Nostr key in the page.** Sign in with an extension (NIP-07: Alby,
  nos2x) or a remote signer (NIP-46: nostrconnect, bunker). Making or
  importing a key is not offered, and refused underneath too.
- **Secrets** (the signer's session, the sync key) in IndexedDB, each
  AES-GCM under a WebCrypto key made non-extractable.
- **Signing out** deletes everything Hopp kept in that browser.
- **No downloads**; playback streams through `<audio>` with the Media
  Session API (lock screen, media keys).
- **One tab at a time**: a second one says Hopp is open elsewhere.
- **Feeds, chapters, transcripts and images through the proxy** — most
  hosts send no CORS headers. The Podcast Index and Sovereigncast's Listener
  API are asked directly (the Listener API needs podcasting_tools !25).

## Check it

```bash
cd web/e2e && npm ci
node run.mjs           # against scripts/web-dev.sh: tour, search, subscribe, play, sign in, reload, tabs, sign out
node run.mjs --sync    # also two browsers syncing over public relays, with a throwaway key
```

A throwaway NIP-07 extension stands in for a real one (`fakeExtension.mjs`).
Compose draws to a canvas and mirrors its buttons into the DOM for screen
readers; the checks press those, as a screen reader does. `CHROME_BIN` picks a
Chrome when Playwright's own is not installed. Screenshots land in `web/e2e/out`.

## On an iPhone

Service workers and passkeys need HTTPS, and a phone reaches neither the
Mac's `127.0.0.1` nor plain HTTP from an HTTPS page. Once:

```bash
brew install mkcert && mkcert -install
mkdir -p web/e2e/certs && mkcert -cert-file web/e2e/certs/cert.pem -key-file web/e2e/certs/key.pem \
    "$(ipconfig getifaddr en0)" localhost 127.0.0.1
open "$(mkcert -CAROOT)"   # AirDrop rootCA.pem to the iPhone, install the profile, then trust it:
                           # Settings → General → About → Certificate Trust Settings
```

Then `scripts/web-dev.sh --lan` and open the address it prints in Safari.
Measure there (ADR 0022, 11.4): the time to the first screen, first visit and
again from the cache, in Safari and added to the home screen; and a VoiceOver
pass over the tour and one list.
