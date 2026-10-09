# Podcasting 2.0 feature matrix

What the two feed sources Hopp must fully support emit today, and when Hopp
supports each tag. Hopp targets the **union** ([ADR 0001](adr/0001-universal-player-with-sovereigncast-layer.md)).

Sources, measured 2026-10-05; the self-hosted column re-measured 2026-10-07
after the phase-0 server work (ADR 0018):
- **Self-hosted** — `podcast_manager/feed_builder.py` in `nodesignal/podcasting_tools`.
- **PodHome** — the live Nodesignal feed (`serve.podhome.fm/rss/e8df0b13-…`, 2.3 MB, ~320 items).

Phase column is a **proposal**, still open for discussion
(P1 = core player, P2 = Sovereigncast layer + richer P2.0, P3 = later).

| Tag | Self-hosted | PodHome | Hopp | Notes |
|---|---|---|---|---|
| `podcast:guid` | ✅ | ✅ | P1 | Identity key for subscriptions and for Sovereigncast verification (ADR 0002). |
| `podcast:value` / `valueRecipient` | ✅ `keysend` and `lnaddress` | ✅ `keysend` **and** `lnaddress` | P1 | Both methods required; a block can mix them. `lnaddress` recipients carry `customKey="lnurl_callback"` on both paths (Sovereigncast's editors resolve it the same way). |
| `podcast:valueTimeSplit` | ✅ inline and `remoteItem` | ✅ (`remoteItem`, music) | P2 | Needs `remoteItem` resolution for remote splits. |
| `podcast:chapters` | ✅ JSON | ✅ JSON | P1 | Chapter images in now-playing. |
| `podcast:transcript` | VTT + SRT (`rel="captions"`), `text/plain` when untimed | SRT + VTT + HTML | P1 | Support SRT, VTT, HTML, JSON, plain text; prefer VTT/JSON. |
| `podcast:person` | ✅ | ✅ | P1 | |
| `podcast:soundbite` | ✅ | ✅ | P1 | Preview / share clips. |
| `podcast:season`, `podcast:episode` | ✅ | ✅ | P1 | Fall back to `itunes:season`/`itunes:episode`. |
| `podcast:image` | ✅ | ✅ | P1 | Responsive artwork. |
| `podcast:funding` | ✅ | ✅ | P1 | |
| `podcast:updateFrequency` | ✅ derived from the publishing gaps | ✅ | P1 | Drives refresh scheduling. |
| `podcast:trailer` | ✅ | ✅ | P2 | |
| `podcast:alternateEnclosure` | ❌ | ✅ (video/mp4) | P2 | Video episodes. |
| `podcast:podroll` / `remoteItem` | ❌ | ✅ | P1 | Podroll recommendations in Discover (ADR 0008); `remoteItem` resolved via Podcast Index. |
| `podcast:medium` | ✅ (always `podcast`) | ✅ | P2 | Music medium → V4V music use case. |
| `podcast:socialInteract` | ✅ (Nostr) | ❌ | P2 | Comments via Nostr. |
| `podcast:txt` | ✅ | ✅ (`purpose="nostr"`) | P2 | `purpose="sovereigncast"` = discovery marker (ADR 0002). |
| `podcast:locked` | ✅ | ✅ | — | Relevant for hosts, not players. |
| `<language>` | ✅ per show | ✅ | P1 | Was hardcoded `en` on the self-hosted path until 2026-10-07. |
| `podcast:podping` | ✅ when pings are configured | ✅ | P3 | A mobile client cannot listen to Podping in the background; needs a relay. |
| `podcast:liveItem`, `podcast:chat` | ❌ | ❌ | P3 | |
| `podcast:location`, `podcast:license` | ❌ | ❌ | P3 | |

## Sovereigncast-only features (Listener API, not in the feed)

Served by the Listener API v1 ([ADR 0010](adr/0010-listener-api-v1.md)),
implemented in Sovereigncast; ecash and push are not part of it yet.

| Feature | Phase |
|---|---|
| Release Boosting: live progress, pay through a Lightning invoice | **P1** (slim layer, ADR 0018) |
| Boost2Unlock: progress, pay through a Lightning invoice | **P1** (slim layer) |
| Halving / Boost2Unlock clips | P2 (P1: where it waits, and a link once unlocked) |
| Topic suggestions: open list, free vote | **P1** (slim layer) |
| Topic suggestions: submit, sats boost | P2 |
| Paid Q&A | P2 |
| Top boosts and the all-time boosters (read-only) | **P1** (pulled forward 2026-10-09, ADR 0019) |
| Top episodes | P2 |
| Ecash (Cashu) redemption, nutzaps | P3 |
| Ecash streaming over a Spilman channel (`ecash_channel` capability, ADR 0014) | P3 |

## Server-side gaps found along the way (Sovereigncast)

Closed on 2026-10-07 (phase 0, ADR 0018), each with tests in podcasting_tools:

- Show language instead of the hardcoded `<language>en</language>`; the
  transcription worker uses it too.
- `podcast:season`/`episode`, `image`, `medium`, `trailer`, `updateFrequency`,
  `podping`, VTT transcripts, `valueTimeSplit` with `remoteItem` (the
  migration import now keeps remote splits as well).
- `lnaddress` recipients turned out to be supported already (both V4V editors
  offer the type and resolve the LNURL callback); only a recipient stored
  without a type was mislabelled as a node. A Spark-backed instance address
  as the default recipient waits for the Spark wallet to land.
- Podcast Index: the show page checks (keylessly) whether the index lists the
  show and shows the feed address to submit or how to claim it. Submitting
  stays manual — the API needs a key with write permission.
- `hopp/` in the download statistics, with Hopp's `; Phone)` device token.

Found by Hopp's spike 2 (open): a Listener API endpoint serving boost
metadata for `rss::payment` comments (ADR 0005), and fee recipients in
`_split_recipients` (percentage off the top, per the value spec).

Still open on the self-hosted path: `podroll`, `alternateEnclosure`,
`license`, `location`, a `medium` other than `podcast`.
