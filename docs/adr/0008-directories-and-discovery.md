# ADR 0008 — Podcast directories and discovery

- **Status:** accepted
- **Date:** 2026-10-06

## Context

| Source | Coverage | P2.0 data | Access | Sees search queries |
|---|---|---|---|---|
| **Podcast Index** | 4M+ feeds, largely includes Apple's catalogue | native: value, person, guid, medium, trending | free API key + secret, requests signed (SHA-1 over key + secret + timestamp) | Podcast Index |
| Apple (iTunes Search API) | very large, mainstream | none — feed URL only | no key, rate-limited | Apple |
| fyyd | strong for German-language shows (used by AntennaPod) | partial | no key | fyyd |
| gpodder.net | stale | none | — | — |
| Spotify exclusives | no RSS — **unreachable by design** | — | — | — |

The Podcast Index enables what P2.0 players are good at: term search filterable
to shows with a Lightning value block, search by `podcast:person`, trending,
categories, medium, and resolving `remoteItem` by GUID (needed for `podroll`
and for remote value-time-splits, ADR 0005).

ADR 0001 left the "Sovereigncast shows in focus" directory for later; this ADR
settles it.

## Decision

### Sources

- **Podcast Index is the only source enabled by default.**
- **fyyd** and **Apple** can be enabled by the listener in the settings, with a
  note on who then sees the search queries.

### Podcast Index key

- Key and secret **ship in the app** (common practice; the Podcast Index hands
  out free keys to apps). No server of ours sees what listeners search.
- If the key is abused, it is rotated with the next release.
- Listeners may enter **their own key** in the settings.
- Rejected: proxying through the push gateway (ADR 0007) — it would keep the
  secret, but we would see every search.

### Sovereigncast shows

- **Badge, always:** when a show is opened, Hopp checks its feed for the
  `podcast:txt purpose="sovereigncast"` marker (with the verification of
  ADR 0002) and marks it as a Sovereigncast show.
- **Directory, phase 1 of it:** a curated JSON list on sovereigncast.space
  (repo `nodesignal/sovereigncast-space`); an instance is added by merge request.
  Feeds a "Sovereigncast shows" section in Discover.
- **Directory, later:** decentralised announcement over Nostr — each instance
  publishes an event signed with the show's key; Hopp queries relays and
  verifies each hit per ADR 0002. Replaces the gatekeeping of the list once
  there are more instances.

### Discover features by phase

- **Phase 1:** term search, direct feed URL, OPML import/export,
  **V4V filter** (only shows with a Lightning value block), **podroll**
  recommendations from subscribed shows (`remoteItem` resolved via Podcast Index).
- **Phase 2:** search by person (`podcast:person`), trending, category browser.

### Show page before subscribing

Search results come from the directory's cached data; the show page fetches
**the actual feed directly** — the only way to show current P2.0 data and to
check the Sovereigncast marker.

## Consequences

- **Server work (podcasting_tools):**
  - A "Register with the Podcast Index" helper for self-hosted shows (submit the
    feed, guide ownership proof via `podcast:txt purpose="verify"`). Without it,
    no P2.0 app — including Hopp — can find a self-hosted show by search.
  - `feed_builder.py` should emit `<podcast:podping usesPodping="true"/>` when
    podping is enabled; sending is already implemented (`podcast_manager/podping.py`)
    but the feed does not tell apps.
  - Optional pointer to Apple Podcasts Connect (manual submission only).
- **Website work (sovereigncast-space):** the curated `shows.json` and a short
  guide for instances to get listed.
- The Podcast Index secret is extractable from the app; this is accepted.
