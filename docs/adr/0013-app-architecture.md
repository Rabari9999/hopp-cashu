# ADR 0013 — App architecture

- **Status:** accepted
- **Date:** 2026-10-07

## Decision

### Project layout

One Kotlin Multiplatform module holds almost everything, organised in packages;
it is split into Gradle modules only when build times or boundaries demand it.

```
hopp/
├── shared/                 Kotlin Multiplatform
│   ├── model/              Show, Episode, Chapter, Person, ValueBlock …
│   ├── feed/               RSS + Podcasting 2.0 parser, chapters JSON,
│   │                       transcripts (SRT, VTT, HTML, JSON)
│   ├── data/               SQLDelight database, repositories
│   ├── net/                Ktor, conditional requests, Podcast Index client
│   ├── value/              value splits, boostagram TLV, LNURL, NWC client
│   ├── sovereigncast/      Listener API v1 client
│   ├── platform/           interfaces: PlaybackEngine, DownloadScheduler,
│   │                       RefreshScheduler, SecureStore, PushRegistrar
│   └── ui/                 Compose Multiplatform screens and view models
├── androidApp/             Media3 service, Android Auto, WorkManager, Keystore
└── iosApp/                 Swift: AVPlayer engine, BGTasks, Keychain, entry point
```

Unidirectional data flow: view models expose a `StateFlow`, the UI renders it.
Dependency injection with Koin; navigation with Navigation Compose
(multiplatform).

### Local data model (SQLDelight)

| Table | Holds |
|---|---|
| `podcast` | feed URL, `podcast_guid`, title, image, language, value block, funding, **Sovereigncast base URL + time of last verification + capabilities**, ETag / Last-Modified, `updateFrequency`, per-show settings (speed, auto-download) |
| `episode` | feed item GUID, title, date, duration, enclosure, image, description, chapter and transcript references, persons, soundbites, value block (JSON), position, played, download state |
| `queue` | queue order |
| `chapter_cache`, `transcript_cache` | fetched chapters and transcripts, available offline |
| `boost_log` | **local record of every boost sent**: amount, recipients, result per recipient — listeners can see where their sats went |
| `stream_ledger` | streaming amounts owed per recipient and not yet paid (sub-sat remainders, minutes listened offline) |

### Feed refresh

- **Each feed fetched directly with conditional requests** (`ETag`,
  `If-Modified-Since`); an unchanged feed costs a `304` and is not parsed.
- Streaming parse; only new or changed items are written.
- Background refresh via WorkManager / `BGAppRefreshTask`, paced by
  `podcast:updateFrequency` (hourly without it), plus pull-to-refresh.
- **Optional, off by default:** a Podcast Index batch check (one request with all
  subscribed GUIDs, then fetch only changed feeds). The setting says plainly that
  the Podcast Index then sees the whole subscription list — more sensitive than
  the search queries it sees anyway (ADR 0008).

### Offline

Library, queue, downloaded episodes, cached chapters and transcripts, and
positions work offline. Streaming sats accumulate in `stream_ledger` (within the
budget) and are paid once the network is back. Boosts are never queued: without
a network the app says so instead of executing a payment later unasked.

### Sovereigncast layer

- `by-guid` verification (ADR 0002) on subscribe, then at most daily; the show's
  capabilities are stored on `podcast`.
- Release Boosting, Boost2Unlock and the leaderboard are polled **only while
  their screen is visible**, paced by `poll_after_seconds`, with `ETag`.
  Background polling is replaced by push later (ADR 0007).
- The client is **handwritten with kotlinx.serialization**. Its tests decode the
  examples from `listener-v1.yaml` (podcasting_tools), which the server's
  contract test validates against the same schemas — the examples are the
  shared fixtures of both sides.

## Consequences

- The `platform/` interfaces are the only place where Android and iOS code
  differ; everything above them is tested once in common code.
- A change to the Listener API contract shows up in Hopp's tests as soon as the
  spec's examples change — the spec file is copied into Hopp's test resources by
  a script and checked for drift.
