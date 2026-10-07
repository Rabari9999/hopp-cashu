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

Decided → [ADR 0011](adr/0011-distribution.md).

## 9. Listener API v1 (server side, in `nodesignal/podcasting_tools`)

Decided → [ADR 0010](adr/0010-listener-api-v1.md).

## 11. Player features in phase 1

Decided → [ADR 0012](adr/0012-player-features-phase-1.md).

## 12. App architecture

Decided → [ADR 0013](adr/0013-app-architecture.md).

## 13. Privacy

Decided → [ADR 0015](adr/0015-privacy.md).

## 14. Languages and accessibility

German and English from the start? Screen reader support, dynamic font sizes,
contrast — what is a release requirement.

## 15. Quality and builds

Test strategy for the shared core and the platform layers, CI on GitLab (Android
on Linux runners; iOS built locally, ADR 0011), release checklist.

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
  gPodder sync, live items, CarPlay, Chromecast, widgets, Tor/proxy setting. (Android Auto and
  Android skip-silence moved to P1 — ADR 0012.)
