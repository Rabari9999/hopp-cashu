# ADR 0007 — Push notifications via WebPush, UnifiedPush and a minimal gateway

- **Status:** accepted
- **Date:** 2026-10-06

## Context

| Event | Time-critical | Source |
|---|---|---|
| New episode of a subscribed show | low | any feed |
| Release Boosting: episode released earlier now | **high** — the core of the live experience | Sovereigncast instance |
| Release Boosting: goal nearly reached | medium | instance |
| Boost2Unlock / halving clip unlocked | medium | instance |

Constraints:

- Hopp has no backend of its own. APNs and FCM need *our* credentials, so
  self-hosted instances cannot push to Hopp users without an intermediary.
- FCM requires Google Play Services; a build with FCM cannot ship on F-Droid.
- iOS background refresh is opportunistic (possibly hours) — useless for
  "released now".

## Decision

### One protocol: WebPush

Sovereigncast instances speak only **WebPush** (RFC 8030 delivery, RFC 8291
payload encryption to the device). Where the endpoint comes from depends on
the platform:

- **Android with UnifiedPush** (ntfy, NextPush, …): endpoint from the
  listener's own distributor. No central infrastructure, no Google.
- **iOS, and Android without a distributor:** endpoint from a minimal
  **Hopp push gateway** on sovereigncast.space, which forwards the encrypted
  payload to APNs / FCM. It cannot read the content and does not know the show;
  it only knows "endpoint → device token". Decryption happens on the device
  (on iOS in a Notification Service Extension).

Hopp registers endpoints with an instance through the Listener API, **per show
and per event type**, only after the listener opts in. The instance learns
only that some device follows *its own* show.

### Events

Pushable, each switchable per show by the listener:

- **New episode** — determined locally for every feed; pushed by Sovereigncast
  instances for their shows.
- **Release Boosting** — released earlier now; goal nearly reached.
- **Boost2Unlock and halving clip** — unlocked.

Not pushable (in-app only): replies to comments/boosts, "your suggestion
became an episode".

### Phases

- **Phase 1:** local background refresh only — WorkManager on Android,
  best-effort `BGAppRefreshTask` on iOS — scheduled by `podcast:updateFrequency`.
- **Phase 2:** WebPush events in the Listener API; UnifiedPush on Android.
- **Phase 2–3:** the gateway (iOS and FCM fallback). Not to be pushed back too
  far: without it, iOS has no live experience for Release Boosting.

### Android build flavors

- **`play`** — UnifiedPush plus FCM fallback via the gateway.
- **`foss`** — UnifiedPush only, no Google dependencies; F-Droid-capable.

## Consequences

- The gateway is the **only server-side service operated for Hopp itself**.
  Data-minimisation promise: it stores only endpoint ↔ device token, no payload,
  no show mapping, no logs of content. It needs the APNs key and the FCM project
  credentials, so it cannot be self-hosted by third parties for this app.
- **Server work (podcasting_tools):** push-subscription endpoints in the
  Listener API (per show, per event type), a WebPush sender (VAPID keys per
  instance), and emitting the events from the Release Boosting / Boost2Unlock
  monitor and the publish scheduler.
- No feed-polling server: new episodes of non-Sovereigncast feeds come only
  from local refresh (a Podping bridge could change that later).
