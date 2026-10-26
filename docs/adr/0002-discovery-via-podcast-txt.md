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
- **Central fallback domain** `https://www.sovereigncast.space/open?feed=…` (www — see the amendment below),
  declared as App Link / Universal Link domain in the app. Opens Hopp when
  installed, otherwise a page with store links. Sovereigncast's community page
  shows this link and its QR code.
  `sovereigncast.space` (repo `nodesignal/sovereigncast-space`, GitLab Pages)
  serves `/.well-known/assetlinks.json` and
  `/.well-known/apple-app-site-association`.
  *To verify:* GitLab Pages serving the extensionless AASA file with an
  acceptable content type.

**Amended 2026-10-12 (implemented, docs/tasks/open-in-hopp-app-link.md):**
the fallback domain is **`www.sovereigncast.space`**, not the bare domain.
`https://sovereigncast.space/…` answers `308` to `www`, and Android does not
follow a redirect when it verifies `/.well-known/assetlinks.json` — the bare
domain can never be verified while it redirects; the redirect also drops the
query string, so `?feed=` is lost even in a browser. `www` serves
`assetlinks.json` directly with the release certificate (docs/signing.md).
Hopp's manifest claims `https://www.sovereigncast.space/open…` (`autoVerify`)
and `hopp://subscribe`; either opens the show's preview on Discover, or its
page on Library when subscribed, and a link without an http(s) `feed` opens
Hopp as it is. Beta and debug builds are signed with the checked-in debug key,
which is not in `assetlinks.json` (and should not be: that key is public) — on
them the App Link stays unverified and the browser keeps it unless the
listener adds the link under the app's "Open by default"; `hopp://` works on
every build.

**Amended 2026-10-12 (sharing, device feedback):** Hopp shares shows,
episodes, chapters and transcript lines with the same link —
`https://www.sovereigncast.space/open?feed=…&episode=<GUID>&t=<seconds>`, for
any show, Sovereigncast or not. It carries the feed, the episode's GUID and a
start, nothing else: no tracking, no Hopp server. Opened in Hopp, a subscribed
show's episode page opens (with "play from" when a start is given); an
unsubscribed show's preview shows the episode on top, "subscribe and listen"
leading to it. The `/open` page passes `episode` and `t` on to `hopp://`. In the
share sheet "from here" is a switch, off by default (user decision); a chapter
or a transcript line is shared from its start.

**Amended 2026-10-26 (sharing, a choice — maintainer, after a listener
preferred AntennaPod's):** the share sheet offers what arrives, not only Hopp's
link: Hopp's link (above), the website the feed names (`<link>`, http(s) only —
AntennaPod's "social message": a short text and that page, a start only in the
text), the audio file's address (only the address; a start as a media fragment,
`#t=<seconds>`, which browsers play from), the downloaded file itself (Android
through the Files app's provider with a one-time read grant, iOS as a named
link in the temporary folder) and, for a show, its feed address. Only what
exists is offered. The last choice comes first next time, on this device only
(`share_as`, not synced).

An opt-in directory of instances is a later phase.

## Consequences

- Server work in Sovereigncast: marker in `feed_builder.py`, public listener URL
  setting, feed check for PodHome shows, `/api/listener/meta`,
  `/api/listener/v1/shows/by-guid/…`, "Open in Hopp" link + QR on the
  community page.
- Website work on sovereigncast.space: `/open` page, `.well-known` files.
