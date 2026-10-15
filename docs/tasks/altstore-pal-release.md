# Hopp for iOS in AltStore PAL

- **Status:** open — step 1 (Apple developer account) not started, 2026-10-15
- **ADR:** 0009 (two iOS builds, `altstore` with the full feature set),
  0011 (distribution), 0004 (bundle ID)

## Why

The first iOS release goes out through AltStore PAL only, without the App
Store. Apple checks such apps with notarization (integrity, malware), not with
App Review's business rules, so the `altstore` build can ship every feature
(ADR 0009). AltStore PAL serves the EU, Japan and Brazil; users need iOS 17.4
(EU), 26.2 (Japan) or 26.5 (Brazil).

Terms as of 2026-10-15 (re-check before signing): since 2026-10-01 Apple
charges a 5 % Core Technology Commission only on digital goods and services
sold through an app distributed outside the App Store; the per-install fee is
gone. Hopp is free and boosts go from the listener's own wallet to third
parties, so nothing should be due — confirm in the terms' text. AltStore PAL
is free for developers.

## Where it stands (2026-10-15)

- No Apple developer account yet. `TEAM_ID` in
  `iosApp/Configuration/Config.xcconfig` is empty; no signing identity on the
  build Mac.
- The iOS app has one target `iosApp` (Debug, Release); there is no
  `altstore` build yet.
- iOS gaps: OPML import and export are no-ops (`IosPlatformActions`),
  downloads run only while the app is open, no `PrivacyInfo.xcprivacy`, no
  `ITSAppUsesNonExemptEncryption` in `Info.plist`.
- App icon (light, dark, tinted) and the privacy policy
  (hopp.sovereigncast.space/privacy/) exist.

## Steps

Maintainer only (Apple account):

1. **Enrol in the Apple Developer Program as an individual** (ADR 0009):
   Apple ID with two-factor authentication, legal name (shown as the
   developer), 99 $/year. Approval takes up to 48 h.
2. **Sign the EU terms** as Account Holder: the Alternative EU Terms Addendum
   (since 2026-08-18 the unified EU terms) for the EU; the current Developer
   Program License Agreement covers Japan and Brazil.
3. **Signing:** put the Team ID into `Config.xcconfig`, register the App ID
   `space.sovereigncast.hopp`, sign in to Xcode on the build Mac so it can
   create the distribution certificate.
4. **Decide the bundle ID for a later `appstore` build** before the first
   upload (it cannot change afterwards, ADR 0004): the same app record for
   both, or a separate one.
5. **App record in App Store Connect:** name, SKU, primary language, privacy
   policy URL, age rating; export compliance (NWC encrypts its messages).

In the repository:

6. **Make the iOS app ready:** an `altstore` build configuration with its
   capability set (ADR 0009), privacy manifest, export-compliance key, close
   the gaps above that matter for a first release.
7. **iOS release script:** archive, export, upload to App Store Connect, like
   `scripts/release.sh` for Android.
8. **Submit for notarization:** App Review → Review Type → "Notarization".

AltStore PAL (docs: https://faq.altstore.io/developers/distribute-with-altstore-pal):

9. Register the developer ID (App Store Connect → Edit Profile; the profile
   UUID, not the Team ID) through AltStore's REST API; enter the returned token
   under Users and Access → Integrations → Marketplace; select Hopp; choose
   "Yes, send notifications".
10. Create an App Store Connect API key (.p8) to download the Alternative
    Distribution Package (ADP).
11. Host the ADP unchanged (directory structure, hashes, `manifest.json`) and
    a source JSON, on hopp.sovereigncast.space.
12. Install on an EU iPhone, list the source on AltStore's explore page,
    announce it in the Nodesignal Telegram group.

## Done when

Hopp installs from AltStore PAL on an iPhone in the EU, and a second build
updates it through the source.
