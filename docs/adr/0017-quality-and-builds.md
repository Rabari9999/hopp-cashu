# ADR 0017 — Quality and builds

- **Status:** accepted
- **Date:** 2026-10-07

## Decision

### Test strategy

| Layer | How | Runs where |
|---|---|---|
| Shared core | unit tests in `commonTest` | JVM in CI; iOS simulator locally on the Mac before iOS releases |
| Feed parser | corpus of real feeds as golden tests — Nodesignal on PodHome, a self-hosted Sovereigncast feed, broken feeds (wrong encoding, missing required fields, multi-MB feeds) | CI |
| Listener API | decode the examples of `listener-v1.yaml` (ADR 0013); a script fails when Hopp's copy drifts from the original in podcasting_tools | CI |
| Value | split math with the same cases as Sovereigncast's `_split_recipients` (largest-remainder rounding); boostagram TLV against bLIP-10 examples; LNURL and NWC against mock servers and a test relay | CI |
| UI | Compose screenshot tests on the JVM (Roborazzi), no emulator | CI |
| Playback and platform | manual checklist on real devices: lock screen, Bluetooth, Android Auto via the Desktop Head Unit, background downloads | before every release |

### CI

- **Own GitLab runner** on our hardware, registered for `nodesignal/hopp` — no
  monthly minute limit; Android builds run natively on amd64. On the current
  Linux box (6 GB RAM, 6 cores, shared) the runner takes **one job at a time**;
  Gradle needs around 4 GB for an Android build.
- On every merge request / push: ktlint + detekt, core tests, both Android
  flavors (debug), screenshot tests.
- On a tag: unsigned release builds (AAB for Play, APK for F-Droid/Zapstore) as
  artifacts.

### Signing stays local

The release key never leaves the maintainer's machine (ADR 0011). CI builds
**unsigned**; the maintainer builds and signs locally; CI then checks that its
unsigned build matches the local one (reproducible builds), which is also what
F-Droid needs to publish our signature.

### Release

- **Changelog** in English and German, two-tier like Sovereigncast (highlights,
  then fixes); the stores' "what's new" text comes from the highlights (Google
  Play: 500 characters per language). Each entry starts with the part of
  the app it belongs to ("**Player:**", "**Wallet:**" …) and says in one short
  sentence what changed for the listener — no technical detail (maintainer,
  2026-10-19).
- **Checklist:** version bump and changelog; Play test track; TestFlight;
  F-Droid metadata merge request; Zapstore publish; screenshots; privacy
  declarations; device checklist; TalkBack/VoiceOver walkthrough of the core
  flows (recommended, not blocking — ADR 0016).

## Consequences

- Setting up and registering the runner is part of phase 0.
- The feed corpus needs a home in the repo (test resources) with the feeds'
  licences in mind — public feeds, stored as snapshots for tests only.
