# Hopp Web on its own server

ADR 0022, 5–7: the client as static files on `play.sovereigncast.space`, the
feed proxy on `feeds.sovereigncast.space` — one small server of their own,
apart from Nodesignal's Sovereigncast instance (maintainer, 2026-10-30), so a
fault in the proxy reaches nothing of it.

| File | What |
|---|---|
| `build.sh [amd64\|arm64]` | a clean web build without source maps, the proxy's address set, Brotli and gzip beside each file; the proxy as a static Linux binary (its tests first) → `build/web-release/<version>/` |
| `build.sh --dev` | the same client, named Hopp Dev with the icons and the mark over the app from `dev/`, version ending in `-dev`, no proxy → `build/web-dev/<version>-dev/` |
| `deploy.sh <server> [--setup]` | an exact tag's build only: the site into `releases/<version>`, `current` switched at once, the last five kept; the proxy and the server files replaced when they changed |
| `deploy.sh <server> --dev` | a `--dev` build into `/srv/hopp-web-dev` the same way; proxy and server files untouched |
| `setup.sh` | a fresh Debian 13 once (and again at any time): updates, security updates by themselves, nftables (22, 80, 443), SSH by key only, Caddy, the proxy's socket and service, the user `deploy` |
| `Caddyfile` | both sites, the headers of ADR 0022, 7, certificates from Let's Encrypt, no access logs |
| `release.mjs <site> [version]` | the build list `release.json` (every file with its SHA-256; its own SHA-256 is the build's hash) and the version in `sw.js` — run by `build.sh` |
| `sign.mjs <site>` | Nodesignal's signature for an exact tag's build (NIP-94, kind 1063), through the Amber bunker in `SIGN_WITH` |
| `zapstore-images.mjs` | not for the web: Hopp's screenshots on Zapstore in `zapstore.yaml`'s order, the app event signed again through the bunker (docs/zapstore.md) |
| `verify.mjs [url]` | the live site against the signature, file by file — also the CI job `web-verify` on a schedule |
| `hopp-feed-proxy.socket`, `.service` | the proxy behind a Unix socket only Caddy may open; systemd denies it every local and private address on top of its own checks |

## Two addresses

ADR 0022, 15. `play.sovereigncast.space` is what listeners use: only builds of
a release tag, signed. `play-dev.sovereigncast.space` takes any stage of the
development branch.

```bash
web/deploy/build.sh --dev
web/deploy/deploy.sh <server> --dev
cd web/e2e && node run.mjs --url https://play-dev.sovereigncast.space/
```

- **An origin of its own:** sign-in, library and service worker are apart from
  `play.`. A path under `play.` would share all of them.
- **Not signed, and it cannot be:** `verify.mjs` takes any signed build as good,
  so a signed dev build would pass on `play.`. `sign.mjs` refuses anything but
  an exact tag; About says the build is not signed. `verify.mjs` is for `play.`.
- **The same relays, the same wallet:** when the sync format changes, use a
  second Nostr identity.
- **One proxy, one `Caddyfile`:** a `--dev` deploy touches neither. Changes to
  them are checked locally (`scripts/web-dev.sh`) and reach the server with
  the next release's deploy, or by deploying the current release's build again.

## The server

One core, 2 GB of memory and 60 GB of disk are plenty: the proxy holds at most
eight fetches at once (32 MB each at most), Caddy serves files compressed at
build time. Traffic is the one thing that grows with listeners — roughly
2 MB per listener and day with covers kept a day by the browser — far inside
what small servers include. Nothing about listeners is stored, so there is
nothing to back up: `setup.sh` and `deploy.sh` make it again.

Access is by a key of its own (`~/.ssh/hopp-web-vps` on the build Mac,
`HOPP_WEB_KEY` for another); root for setting up, `deploy` for the site.

```bash
web/deploy/build.sh amd64
(cd web/deploy && npm ci)                                        # once, for sign.mjs and verify.mjs
SIGN_WITH='bunker://…' node web/deploy/sign.mjs build/web-release/<version>/site
web/deploy/deploy.sh <server> --setup     # the first time; later without --setup
node web/deploy/verify.mjs                # what the server gives out, against the signature
```

Signed before it goes out: a listener's Hopp compares the build with the
signature under Settings → About, and the scheduled `web-verify` fails on a
build nobody signed. Built the same from the same commit, a build has the
same hash — anyone can rebuild and compare (ADR 0022, 14).

Going back to an earlier site: `ssh deploy@<server> 'cd /srv/hopp-web && ls releases && ln -sfn releases/<version> current'`.

## Checked locally

`scripts/web-dev.sh --strict` serves with the headers from the `Caddyfile`,
and `web/e2e/run.mjs` fails on anything the CSP refuses. Found that way
(2026-10-30): the splash's inline style (now `splash.css`), Compose's one
`<style>` in its shadow root (allowed by hash), Coil decoding covers in a
worker made from a blob (`worker-src blob:` — refused, it ended in an
uncaught "illegal cast"), and the Media Session's cover for the lock screen
(`img-src https:`).

## Before it is linked anywhere

The sites answer with `X-Robots-Tag: noindex` until the imprint and the
privacy policy are online (ADR 0022, 11.6) — the proxy processes IP
addresses, so the privacy policy names this server, its host and location.
