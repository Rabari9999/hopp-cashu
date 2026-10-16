# Hopp for iOS in AltStore PAL

- **Status:** open — the repository is ready for a release from another
  developer's account (2026-10-16); the Apple and AltStore steps are left
- **ADR:** 0009 (two iOS builds, amended: first release through a befriended
  developer's account), 0004 (`.pal` bundle ID), 0011 (distribution)
- **How:** docs/ios-release.md

## Why

The first iOS release goes out through AltStore PAL only, without the App
Store. Apple checks such apps with notarization (integrity, malware), not with
App Review's business rules, so the build can ship every feature (ADR 0009).
AltStore PAL serves the EU, Japan and Brazil; users need iOS 17.4 (EU), 26.2
(Japan) or 26.5 (Brazil). A befriended developer with an existing Apple
account uploads and notarizes it; Nodesignal's own account comes later, with
the `appstore` build.

Terms as of 2026-10-15 (re-check before signing): since 2026-10-01 Apple
charges a 5 % Core Technology Commission only on digital goods and services
sold through an app distributed outside the App Store. Hopp is free and
boosts go from the listener's own wallet to third parties, so nothing should
be due — confirm in the terms' text. AltStore PAL is free for developers.

## Where it stands (2026-10-16)

In the repository (done):

- Team ID and a developer's own bundle ID in `Local.xcconfig`, ignored by git
  (`Local.xcconfig.example`); the PAL build's bundle ID
  `space.sovereigncast.hopp.pal` is set by `scripts/release-ios.sh`.
- `scripts/release-ios.sh`: archive, sign, upload from a tag.
- `ITSAppUsesNonExemptEncryption` and a privacy manifest.
- The Xcode build phase gives Gradle 6 GB: with the 3 GB of
  `gradle.properties` linking the release framework ran out of memory.
- `altstore/source.json` as a template; docs/ios-release.md.

Not in the app yet (no blocker for a first test release):

- No URL schemes in `Info.plist`: `hopp://subscribe` and
  `nostr+walletconnect://` do not open Hopp; "Open in Hopp" links need
  Associated Domains and an `apple-app-site-association` with the releasing
  team's ID on sovereigncast.space.
- No QR scan (`canScanQr` is false), OPML import and export are no-ops,
  downloads run only while the app is open, no external Nostr signer, no boost
  notifications.
- Hardly checked on a real iPhone (the checklist in docs/spikes/05-ios.md).
- No iPhone screenshots for the source.

## Steps left

1. Developer: the Mac and the one-time Apple and AltStore steps
   (docs/ios-release.md, "Once").
2. Maintainer: tag the release; developer: `scripts/release-ios.sh vX.Y.Z`,
   export compliance (French declaration), submit for notarization.
3. ADP and `source.json` onto hopp.sovereigncast.space under `/altstore/`.
4. Install on an EU iPhone, list the source on AltStore's explore page,
   announce it in the Nodesignal Telegram group.

## Done when

Hopp installs from AltStore PAL on an iPhone in the EU, and a second build
updates it through the source.
