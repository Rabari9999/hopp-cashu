# Release checklist

ADR 0017's checklist, written out for Android. Every phase ends with it
(ADR 0018). Tick the boxes in the release's issue, not here.

## Before the tag

- [ ] `CHANGELOG.md` and `CHANGELOG.de.md`: move `[Unreleased]` into
      `## [x.y.z] - YYYY-MM-DD`; highlights above Fixes, the stores' text cut
      from them (Play: ≤ 500 characters per language).
- [ ] `androidApp/build.gradle.kts`: `appVersion` and `versionCode` (+1 for
      every upload — Play refuses a code it has seen).
- [ ] `fdroid/space.sovereigncast.hopp.yml`: a new `Builds` entry,
      `CurrentVersion`, `CurrentVersionCode`.
- [ ] Device checklist on a real phone, with the **beta** build of the release
      commit (R8 is on — this is the build that finds a missing keep rule):
  - [ ] subscribe (search, feed address, OPML), refresh, unsubscribe
  - [ ] play, pause, seek, chapters (‹ ›, swipe on the cover, long-press ↺/↻)
  - [ ] lock screen and notification: controls, chapter title and image,
        quick boost
  - [ ] Bluetooth headset: play/pause, next/previous
  - [ ] Android Auto via the Desktop Head Unit: browse, play
  - [ ] background download on Wi-Fi, resume after airplane mode, cleanup
  - [ ] sleep timer: minutes, end of chapter, end of episode
  - [ ] wallet: connect over NWC (paste and `nostr+walletconnect:` link),
        boost an episode and a show, streaming on/off, history
  - [ ] Sovereigncast show: badge, Release Boosting details and payment,
        vote, Boosts tab, boosted sats per episode
  - [ ] transcript, people, privacy page
  - [ ] after an update over the previous version: library, positions and
        wallet are still there (database migration)
- [ ] TalkBack walkthrough of the core flows (recommended, not blocking —
      ADR 0016).
- [ ] Store screenshots still match the app.

## Tag and builds

- [ ] `git tag vX.Y.Z && git push origin vX.Y.Z` — CI's `release-unsigned`
      job builds the unsigned APK (foss) and AAB (play).
- [ ] Build and sign locally with the release key (`docs/signing.md`); check
      the signed APK against CI's with `scripts/compare_apks.py`.
- [ ] `apksigner verify --print-certs` shows the release certificate.

## Publish

- [ ] **Google Play:** upload the AAB to the closed test track first; the
      first release needs 12 testers for 14 days before production. Privacy
      policy URL (sovereigncast.space) and the data-safety form ("no data
      collected", ADR 0015).
- [ ] **GitLab release** with the signed foss APK (`hopp-X.Y.Z-foss.apk`, the
      name F-Droid's `Binaries` expects).
- [ ] **Zapstore:** publish the signed foss APK.
- [ ] **F-Droid:** merge request to fdroiddata with the updated metadata
      (first time: the inclusion request — open question 17 first).
- [ ] Tell the testers (Signal) what changed — from the changelog's
      highlights.
