# ADR 0003 — Kotlin Multiplatform + Compose Multiplatform

- **Status:** accepted
- **Date:** 2026-10-05

## Context

Android ships first, iOS must follow in parallel. In a podcast player the
quality-defining parts are platform-native in **every** stack:

| Concern | Android | iOS |
|---|---|---|
| Background playback, lock screen, headset controls | Media3 (ExoPlayer + `MediaSessionService`) | AVPlayer + `MPNowPlayingInfoCenter` / `MPRemoteCommandCenter` |
| Car | Android Auto via `MediaLibraryService` | CarPlay (audio entitlement, granted by Apple on request) |
| Background downloads | WorkManager | Background `URLSession` |
| Background feed refresh | WorkManager | `BGAppRefreshTask` (time-limited by iOS) |

With Flutter or React Native these live in plugins; when a plugin falls short
you write Kotlin/Swift anyway, behind platform channels, in a third language.

## Decision

- **Kotlin Multiplatform** shared core: feed parsing, local database
  (SQLDelight), networking (Ktor), Sovereigncast Listener API client, value-split
  calculation, boostagram construction, streaming-sats logic, NWC client.
- **Compose Multiplatform** shared UI (stable on iOS since 2025). Individual
  screens may be done natively in SwiftUI where needed.
- Platform-native implementations of the concerns above behind shared
  interfaces (e.g. `PlaybackEngine`, `DownloadScheduler`).
- Precedent: ACINQ's Phoenix wallet (shared Kotlin core with Lightning logic).
  Its `secp256k1-kmp` covers the Nostr/NWC cryptography in shared code.

## Spikes before committing to the structure

1. **XML parsing in common code** (xmlutil or ksoup) — robust against malformed
   real-world feeds.
2. **Rust libraries on iOS.** rust-nostr, Breez Spark SDK and CDK (Cashu) ship
   Kotlin and Swift bindings via UniFFI, but the Kotlin bindings are JVM/JNA and
   do not run on Kotlin/Native. Plan: NWC in pure Kotlin (phase 1 needs no Rust
   library); a built-in wallet later sits behind an interface implemented with
   the Swift bindings on iOS (or via Gobley — to evaluate).
3. **Background feed refresh on iOS** under `BGAppRefreshTask` limits.
4. **Playback** on both platforms: chapters with images in now-playing,
   gapless resume, playback speed.

## Consequences

- Android builds and shared-core tests run on Linux; **iOS builds require
  macOS** (the maintainer's Apple-Silicon machine or macOS CI runners).
- Whether an Android emulator runs in the Linux dev container (nested, likely
  no KVM) is to be checked during the spikes.
- Flutter would have reached a first MVP faster; the trade-off was accepted in
  favour of first-class native playback, Auto/CarPlay and downloads.
