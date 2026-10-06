# ADR 0002 — Discovery via `<podcast:txt purpose="sovereigncast">`

- **Status:** accepted
- **Date:** 2026-10-05

## Context

Hopp has to recognise that a feed belongs to a Sovereigncast instance and find
that instance's API — for both hosting modes:

- **Self-hosted:** Sovereigncast builds the feed itself (`feed_builder.py`).
- **PodHome-hosted:** PodHome builds the feed; Sovereigncast only manages the
  show. Sovereigncast cannot inject arbitrary tags, but PodHome does pass
  through custom `<podcast:txt>` records (the Nodesignal feed already carries
  `<podcast:txt purpose="nostr">`).

## Decision

### 1. Feed marker

```xml
<podcast:txt purpose="sovereigncast">https://instance.example</podcast:txt>
```

The value is the instance's **public base URL only**. Hopp calls
`GET <base>/api/listener/meta` to learn supported API versions and
capabilities, so an API version bump never requires a feed change.

- Self-hosted: `feed_builder.py` emits it automatically.
- PodHome: the podcaster adds it once in the PodHome UI. Sovereigncast
  periodically reads the show's feed and shows "marker found ✅ / missing ❌"
  with copy-ready text in the show settings.
  *To verify:* that PodHome accepts arbitrary `purpose` values.

### 2. Mutual verification

A feed can point at any URL, so the feed alone is not trusted. Hopp calls
`GET <base>/api/listener/v1/shows/by-guid/<podcast:guid>`; the instance must
confirm that it manages that GUID, and the feed URL it reports must match the
subscribed one. Only then does the Sovereigncast layer activate. This stops a
foreign feed from embedding another show's Release Boosting widget.

The response also carries the **per-show feature toggles** (V4V, leaderboard,
halving clip, …) so Hopp only shows what the show actually uses.

### 3. Explicit public listener URL on the server

Today the server derives its own URL from the current request
(`normalize_public_base_url(request.url_root, …)`). That works for a feed
request, but a PodHome-mode instance never sees one, and admins on Umbrel may
reach it as `umbrel.local`. Sovereigncast gets an explicit **"public listener
URL"** setting; the marker is emitted only when it is set and uses `https`.
An instance that is only reachable on the LAN has no listener features — same
as its community page today.

### 4. Deep links

Android App Links and iOS Universal Links only work for domains fixed **at app
build time**, so arbitrary self-hosted instance domains cannot be covered.
Two mechanisms:

- **Custom scheme** `hopp://subscribe?feed=<url-encoded feed URL>` — works
  whenever the app is installed. (Schemes are not registered anywhere; another
  app could claim `hopp://` too, which is why it is not the primary link.)
- **Central fallback domain** `https://sovereigncast.space/open?feed=…`,
  declared as App Link / Universal Link domain in the app. Opens Hopp when
  installed, otherwise a page with store links. Sovereigncast's community page
  shows this link and its QR code.
  `sovereigncast.space` (repo `nodesignal/sovereigncast-space`, GitLab Pages)
  serves `/.well-known/assetlinks.json` and
  `/.well-known/apple-app-site-association`.
  *To verify:* GitLab Pages serving the extensionless AASA file with an
  acceptable content type.

An opt-in directory of instances is a later phase.

## Consequences

- Server work in Sovereigncast: marker in `feed_builder.py`, public listener URL
  setting, feed check for PodHome shows, `/api/listener/meta`,
  `/api/listener/v1/shows/by-guid/…`, "Open in Hopp" link + QR on the
  community page.
- Website work on sovereigncast.space: `/open` page, `.well-known` files.
