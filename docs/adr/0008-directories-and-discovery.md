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
- **Public in the repository** (decided 2026-10-10, open question 17): the
  F-Droid build from source uses the same key as our signed APK, so the
  builds match. AntennaPod and KDE Kasts publish theirs the same way;
  Podverse instead proxies the index through its own server, which ADR 0015
  rules out for Hopp.
- ~~Listeners may enter **their own key** in the settings.~~ Withdrawn
  2026-10-08, after the field was on the device: it confused far more people
  than it would ever help, and a key abused or rate-limited is rotated with a
  release anyway. Should F-Droid ship without the key (open question 17), a
  hidden advanced field is the place to bring it back.
- Rejected: proxying through the push gateway (ADR 0007) — it would keep the
  secret, but we would see every search.

### Sovereigncast shows

- **Badge, always:** when a show is opened, Hopp checks its feed for the
  `podcast:txt purpose="sovereigncast"` marker (with the verification of
  ADR 0002) and marks it as a Sovereigncast show.
- **Directory, phase 1 of it:** a curated JSON list on sovereigncast.space
  (repo `nodesignal/sovereigncast-space`); an instance is added by merge request.
  Feeds a "Sovereigncast shows" section in Discover. *Built 2026-10-10:* `https://sovereigncast.space/directory.json`
  (`ScDirectory` in Hopp) marks listed shows in search and backs the
  Sovereigncast filter, which without a term lists them all. No directory —
  Podcast Index included — exposes `podcast:txt`, and fetching each result's
  feed for the marker would cost a request per row, hence the list.
- **Directory, later:** decentralised announcement over Nostr — each instance
  publishes an event signed with the show's key; Hopp queries relays and
  verifies each hit per ADR 0002. Replaces the gatekeeping of the list once
  there are more instances.

### Discover features by phase

- **Phase 1:** term search, direct feed URL, OPML import/export,
  **V4V filter** (only shows with a Lightning value block), **podroll**
  recommendations from subscribed shows (`remoteItem` resolved via Podcast Index).
- **Phase 2:** search by person (`podcast:person`), trending, category browser.

  *Amended 2026-10-19 (maintainer): trending, categories and new V4V shows came
  forward.* Discover without a term shows category chips (the listener's own
  first, then all 19 main categories — Apple's top level, which feeds name in
  `itunes:category` and the index files shows under; its sub-names count for
  their main one), **For you** (the podroll with the show that recommends it,
  topped up with what is popular in the two categories the listener subscribed
  most), **Popular right now** (`podcasts/trending` in the device's language,
  in the chosen category) and **New with ⚡ Value** (`recent/newvaluefeeds`,
  the device's language first, topped up from all). Answers are kept for an
  hour. The index sees the language and the category, never the
  subscriptions. A show's page shows its main categories as tags that open
  them here; the library sorts by newest episode (the default), A–Z or most
  unheard, and its list names the newest episode's day and the first
  category.

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
