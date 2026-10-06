# Open questions

Topics still to be decided, in the order we plan to go through them. Each
carries the current **proposal**; once decided it becomes an ADR.

## 4. Wallet strategy

Decided → [ADR 0005](adr/0005-wallet-strategy.md).

## 5. Identity

Proposal: **no account; optional Nostr identity** via Amber (NIP-55, Android)
or a remote signer (NIP-46, both platforms). Unlocks `socialInteract`
comments, a sender name on boosts, and encrypted subscription sync
(e.g. NIP-78 app data). Mirrors Sovereigncast's existing Nostr login.

## 6. Push notifications

Proposal: **none in the MVP.** Self-hosted instances cannot each hold APNs/FCM
credentials for our app; push would need a central relay, which cuts against
the sovereignty promise. MVP uses background refresh; later a small relay or
UnifiedPush on Android. Release Boosting ("episode out now") is the strongest
use case.

## 7. iOS App Store risk

Paying to *unlock* content is what Apple polices (IAP rules); boosts/tips are
established (Fountain, Podverse). Boost2Unlock is a collective unlock, not an
individual purchase, but it is grey area. Proposal: every Sovereigncast feature
is **switchable per platform** so iOS review cannot block the Android release.

## 8. Distribution

Proposal: Google Play, Zapstore (Nostr app store), possibly F-Droid
(check dependencies for non-free components); iOS via TestFlight → App Store.

## 9. Listener API v1 (server side, in `nodesignal/podcasting_tools`)

Scope, auth model (public read, invoices for writes), rate limiting, versioning,
OpenAPI contract as source of truth. See [ADR 0002](adr/0002-discovery-via-podcast-txt.md)
for the discovery endpoints already decided.

## 10. Phase plan

Draft:
- **P0** — spikes (ADR 0003), Listener API v1 spec, close Sovereigncast feed gaps.
- **P1** — core player: subscribe (URL / Podcast Index / OPML), playback with
  chapters, transcripts, persons, soundbites, downloads, boosts + streaming via NWC.
- **P2** — Sovereigncast layer + richer P2.0 tags (see feature matrix).
- **P3** — built-in wallet, ecash, sync, push, live items, Android Auto / CarPlay
  (unless pulled earlier).
