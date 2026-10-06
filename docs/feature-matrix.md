# Podcasting 2.0 feature matrix

What the two feed sources Hopp must fully support emit today, and when Hopp
supports each tag. Hopp targets the **union** ([ADR 0001](adr/0001-universal-player-with-sovereigncast-layer.md)).

Sources, measured 2026-10-05:
- **Self-hosted** — `podcast_manager/feed_builder.py` in `nodesignal/podcasting_tools`.
- **PodHome** — the live Nodesignal feed (`serve.podhome.fm/rss/e8df0b13-…`, 2.3 MB, ~320 items).

Phase column is a **proposal**, still open for discussion
(P1 = core player, P2 = Sovereigncast layer + richer P2.0, P3 = later).

| Tag | Self-hosted | PodHome | Hopp | Notes |
|---|---|---|---|---|
| `podcast:guid` | ✅ | ✅ | P1 | Identity key for subscriptions and for Sovereigncast verification (ADR 0002). |
| `podcast:value` / `valueRecipient` | ✅ keysend only | ✅ `keysend` **and** `lnaddress` | P1 | Both methods required. PodHome's `lnaddress` recipients carry `customKey="lnurl_callback"`. |
| `podcast:valueTimeSplit` | ❌ (data exists in `v4v_time_splits`, not rendered) | ✅ | P2 | Needs `remoteItem` resolution for remote splits. |
| `podcast:chapters` | ✅ JSON | ✅ JSON | P1 | Chapter images in now-playing. |
| `podcast:transcript` | SRT only | SRT + VTT + HTML | P1 | Support SRT, VTT, HTML, JSON; prefer VTT/JSON. |
| `podcast:person` | ✅ | ✅ | P1 | |
| `podcast:soundbite` | ✅ | ✅ | P1 | Preview / share clips. |
| `podcast:season`, `podcast:episode` | ❌ (only `itunes:*`) | ✅ | P1 | Fall back to `itunes:season`/`itunes:episode`. |
| `podcast:image` | ❌ | ✅ | P1 | Responsive artwork. |
| `podcast:funding` | ✅ | ✅ | P1 | |
| `podcast:updateFrequency` | ❌ | ✅ | P1 | Drives refresh scheduling. |
| `podcast:trailer` | ❌ | ✅ | P2 | |
| `podcast:alternateEnclosure` | ❌ | ✅ (video/mp4) | P2 | Video episodes. |
| `podcast:podroll` / `remoteItem` | ❌ | ✅ | P1 | Podroll recommendations in Discover (ADR 0008); `remoteItem` resolved via Podcast Index. |
| `podcast:medium` | ❌ | ✅ | P2 | Music medium → V4V music use case. |
| `podcast:socialInteract` | ✅ (Nostr) | ❌ | P2 | Comments via Nostr. |
| `podcast:txt` | ✅ | ✅ (`purpose="nostr"`) | P2 | `purpose="sovereigncast"` = discovery marker (ADR 0002). |
| `podcast:locked` | ✅ | ✅ | — | Relevant for hosts, not players. |
| `podcast:podping` | ❌ | ✅ | P3 | A mobile client cannot listen to Podping in the background; needs a relay. |
| `podcast:liveItem`, `podcast:chat` | ❌ | ❌ | P3 | |
| `podcast:location`, `podcast:license` | ❌ | ❌ | P3 | |

## Sovereigncast-only features (Listener API, not in the feed)

All currently behind unversioned `/api/community/*` endpoints shaped for the
community web page; they need a stable Listener API v1 first.

| Feature | Phase |
|---|---|
| Release Boosting (live progress, boost the next release) | P2 |
| Boost2Unlock (progress, contribute, halving-clip unlock at 50 %) | P2 |
| Topic suggestions: open list, free vote, sats boost, submit | P2 |
| Paid Q&A | P2 |
| Top boosts / top boosters / top episodes | P2 |
| Ecash (Cashu) redemption, nutzaps | P3 |

## Server-side gaps found along the way (Sovereigncast)

- `feed_builder.py` hardcodes `fg.language('en')` — wrong for German shows,
  affects every podcast app.
- No Podcast Index registration for self-hosted shows, so search cannot find
  them; `<podcast:podping usesPodping="true"/>` is not emitted although podping
  sending exists (ADR 0008).
- Value recipients are keysend-only; adding `lnaddress` recipients lets boosts
  arrive from wallets that cannot send keysend (ADR 0005).
- Self-hosted feed lacks `podcast:season`/`episode`, VTT/JSON transcripts,
  `valueTimeSplit`, `image`, `trailer`, `updateFrequency`, `medium`, `podroll`,
  `alternateEnclosure`, `podping` — all of which the PodHome path delivers.
