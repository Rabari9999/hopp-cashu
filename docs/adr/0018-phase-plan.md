# ADR 0018 — Phase plan

- **Status:** accepted
- **Date:** 2026-10-07
- **Summarises:** ADRs 0001–0017

## Decision

### Phase 0 — foundation and spikes

- Project skeleton per ADR 0013; own GitLab runner (ADR 0017).
- **Spikes:** XML parsing against broken real-world feeds; playback with chapters
  on Android; NWC in pure Kotlin with keysend and Lightning-address payments, and
  how boostagram metadata travels over LNURL (ADR 0005); reproducible builds and
  the F-Droid recipe (ADR 0011).
- **Minimal iOS spike:** the shared core compiles for iOS and an AVPlayer engine
  plays an episode behind the `PlaybackEngine` interface — so the architecture
  does not drift somewhere iOS cannot follow while iOS work waits (see below).
- Accounts: Google Play developer account (the closed 14-day test is on the
  critical path).
- Logo finalised (brief in `docs/brand/`); sovereigncast.space gets `/open` and
  the `.well-known` app-link files (ADR 0002).
- **Sovereigncast (podcasting_tools):** feed gaps (hardcoded language,
  `podcast:season`/`episode`, VTT transcripts, `valueTimeSplit`,
  `updateFrequency`, the podping tag), Lightning-address value recipients
  (ADR 0005), a Podcast Index registration helper (ADR 0008), `hopp/` in the
  download statistics (ADR 0015). The Listener API v1 itself is done.
  *All done 2026-10-07* — see the feature matrix's server-side section.

### Phase 1 — first Android release

- Player features per ADR 0012, including Android Auto and skip-silence.
- Subscribe and discover per ADR 0008 (search with V4V filter, feed URL, OPML,
  podroll).
- Boosts and Lightning streaming via NWC, with accrual and settlement per
  ADR 0014; offline behaviour per ADR 0013.
- **A slim Sovereigncast layer** — the feature that sets Hopp apart, against an
  API that already exists: detect and verify the show (ADR 0002); show Release
  Boosting and Boost2Unlock progress and pay through a Lightning invoice
  (NWC or `lightning:` link, amount checked per ADR 0005); list open
  suggestions and vote for free.
- Privacy page (ADR 0015), English and German (ADR 0016).
- **Release path:** a closed beta with the Nodesignal community first, then
  public on Zapstore and F-Droid; Google Play later (ADR 0011, amended
  2026-10-10).

### Phase 2

- The full Sovereigncast layer: submit and boost suggestions, unlocked clips
  in the player, top episodes. *Built 2026-10-14.* (The leaderboard, read-only, moved into phase 1
  on 2026-10-09 — ADR 0019.)
- Optional Nostr identity with comments and sync (ADR 0006; how, in five
  stages: ADR 0021). *All five built by 2026-10-20; sync was taken before
  push (maintainer, 2026-10-20).*
- WebPush and UnifiedPush (ADR 0007).
- Person search, trending, categories (ADR 0008); video via
  `alternateEnclosure`; `valueTimeSplit` with `remoteItem`. *Person search
  built 2026-10-27 (0.3.1); video put back (maintainer, same day).*
- Sovereigncast show directory as a curated list (ADR 0008). *Built in
  phase 1 (2026-10-10): `sovereigncast.space/directory.json`.*
- `podcast:trailer` on the show page. *Built 2026-10-15.*

*Amended 2026-10-15:* the full Sovereigncast layer was built on 2026-10-14.
"Paid Q&A", which ADR 0001 and the feature matrix listed, is not a feature of
its own: listeners' paid questions are the topic suggestions, built with it.

### After phase 2 — iOS

iOS work starts once Android has reached phase 2: the `appstore` and `altstore`
builds (ADR 0009), TestFlight, Apple developer account, the push gateway for
APNs (ADR 0007). The shared core and the phase-0 spike keep this a port of the
platform layer, not a second app.

### Phase 3

Built-in Spark and Cashu wallets with Spilman and NUT-18 streaming (ADR 0014),
Nostr-based show directory, gPodder sync, live items, CarPlay, Chromecast,
widgets, a Tor/proxy setting.

## Consequences

- The push gateway, until now "phase 2–3", is needed for FCM fallback on Android
  in phase 2 and for iOS after it.
- The feature matrix moves Release Boosting / Boost2Unlock (progress and paying)
  and the open-suggestions list with free votes into phase 1.
- Every phase ends with the release checklist of ADR 0017.
