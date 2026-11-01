# Hopp Web on its own server

ADR 0022, 5–7: the client as static files on `play.sovereigncast.space`, the
feed proxy on `feeds.sovereigncast.space` — one small server of their own,
apart from Nodesignal's Sovereigncast instance (maintainer, 2026-10-30), so a
fault in the proxy reaches nothing of it.

| File | What |
|---|---|
| `build.sh [amd64\|arm64]` | a clean web build without source maps, the proxy's address set, Brotli and gzip beside each file; the proxy as a static Linux binary (its tests first) → `build/web-release/<version>/` |
| `deploy.sh <server> [--setup]` | the site into `releases/<version>`, `current` switched at once, the last five kept; the proxy and the server files replaced when they changed |
| `setup.sh` | a fresh Debian 13 once (and again at any time): updates, security updates by themselves, nftables (22, 80, 443), SSH by key only, Caddy, the proxy's socket and service, the user `deploy` |
| `Caddyfile` | both sites, the headers of ADR 0022, 7, certificates from Let's Encrypt, no access logs |
| `hopp-feed-proxy.socket`, `.service` | the proxy behind a Unix socket only Caddy may open; systemd denies it every local and private address on top of its own checks |

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
web/deploy/deploy.sh <server> --setup     # the first time; later without --setup
```

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
