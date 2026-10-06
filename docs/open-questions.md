# Open questions

Topics still to be decided, in the order we plan to go through them. Each
carries the current **proposal**; once decided it becomes an ADR.

## 4. Wallet strategy

Decided → [ADR 0005](adr/0005-wallet-strategy.md).

## 5. Identity

Decided → [ADR 0006](adr/0006-identity.md).

## 6. Push notifications

Decided → [ADR 0007](adr/0007-push-notifications.md).

## Directories and discovery

Decided → [ADR 0008](adr/0008-directories-and-discovery.md).

## 7. iOS App Store risk

Decided → [ADR 0009](adr/0009-ios-app-store-strategy.md).

## 8. Distribution

Proposal: Google Play, Zapstore (Nostr app store), possibly F-Droid
(check dependencies for non-free components); iOS via TestFlight → App Store.
Already fixed: Android ships as `play` (FCM fallback) and `foss` (UnifiedPush
only) — ADR 0007; iOS as `appstore` (conservative) and `altstore` (AltStore
PAL, full) — ADR 0009. Open: F-Droid vs. own repo, Zapstore, release cadence.

## 9. Listener API v1 (server side, in `nodesignal/podcasting_tools`)

Decided → [ADR 0010](adr/0010-listener-api-v1.md).

## 10. Phase plan

Draft:
- **P0** — spikes (ADR 0003, 0005), Listener API v1 spec, close Sovereigncast
  feed gaps (feature matrix).
- **P1** — core player: subscribe (search with V4V filter, feed URL, OPML,
  podroll), playback with chapters, transcripts, persons, soundbites, downloads,
  boosts + streaming via NWC, local background refresh.
- **P2** — Sovereigncast layer, optional Nostr identity + sync, WebPush /
  UnifiedPush, person search, trending, categories, richer P2.0 tags.
- **P2–3** — push gateway for iOS / FCM.
- **P3** — built-in Spark + Cashu wallets, Nostr-based Sovereigncast directory,
  gPodder sync, live items, Android Auto / CarPlay (unless pulled earlier).
