# ADR 0011 — Distribution

- **Status:** accepted
- **Date:** 2026-10-07

## Context

ADR 0007 fixed two Android flavors (`play` with FCM fallback, `foss` with
UnifiedPush only) and ADR 0009 two iOS builds (`appstore`, `altstore`). This ADR
decides where they are published, how they are signed and how releases are paced.

## Decision

### Android channels at launch

| Channel | Flavor | Notes |
|---|---|---|
| **Google Play** | `play` | New personal developer accounts must run a closed test with at least 12 testers for 14 days before the first production release (as of 2026, to verify when the account is created) — plan it early; the Nodesignal community can provide testers. |
| **F-Droid main repository** | `foss` | F-Droid builds from source and requires free dependencies only. Releases arrive days to weeks after tagging. Possible anti-feature labels (e.g. for the Podcast Index API as a central service) are to be expected and accepted. |
| **Zapstore** | `foss` | Nostr-based store, published with our npub; APK from the GitLab release. `foss` because its users often run without Google services. |

Not at launch: IzzyOnDroid, direct APK downloads / Obtainium (GitLab releases
carry the APKs anyway, so this costs nothing to add later).

### Signing: one key everywhere

All Android builds are signed with **one key we hold**. For Google Play the same
key is uploaded to Play App Signing ("use your own key"). Same application ID +
same key means a listener can move between Play and Zapstore (and between the
`play` and `foss` flavors) as a normal update, without losing data.

**F-Droid needs reproducible builds for this.** It signs with its own key unless
its build from source is byte-identical to our signed APK; then it publishes
ours. Reproducibility for a Kotlin/Compose app (pinned JDK and Gradle, no
timestamps, deterministic R8) becomes a phase-0 spike. If it fails, F-Droid
ships with its key and F-Droid installs cannot switch to the other channels
without reinstalling — common for open-source apps, and acceptable.

The signing key and its backup are kept offline; losing it means a new
application ID on every channel.

### iOS

TestFlight for betas (public link, up to 10,000 external testers), then the App
Store (`appstore` build) and AltStore PAL in the EU (`altstore` build, ADR 0009).
**Builds are made locally on the maintainer's Apple-Silicon Mac**, like the
Sovereigncast Docker images — no paid macOS CI runners.

### Release cadence: Android first

Android ships when it is ready; iOS follows with **its own version line**. Both
talk to the same Sovereigncast Listener API v1, so a version gap between the
platforms does not affect compatibility with instances.

## Consequences

- Phase 0 gets two more spikes: reproducible Android builds, and the F-Droid
  build recipe (metadata merge request to fdroiddata) for the `foss` flavor.
- Phase 3 wallets (Breez Spark, CDK) ship prebuilt native libraries; for the
  F-Droid main repository they must either build from source in F-Droid's
  build server or stay out of the `foss` flavor. Decided when phase 3 starts.
- The Google Play closed-test period sits on the critical path to the first
  public Android release.
