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
        vote, Boosts tab
  - [ ] transcript, people, privacy page
  - [ ] after an update over the previous version: library, positions and
        wallet are still there (database migration)
- [ ] TalkBack walkthrough of the core flows (recommended, not blocking —
      ADR 0016).
- [ ] Store screenshots still match the app.

## Tag and builds

- [ ] `git tag vX.Y.Z && git push origin vX.Y.Z` — CI's `release-unsigned`
      job builds the unsigned APK (foss) and AAB (play).
- [ ] Optional, for F-Droid: build the release locally and check it against
      CI's unsigned APK with `scripts/compare_apks.py` (reproducible builds).
- [ ] `scripts/release.sh vX.Y.Z /path/to/release-keystore [alias]` — signs
      CI's unsigned APK, checks the release certificate, uploads it and
      creates the GitLab release (next section's first box).

## Publish

- [ ] **GitLab release** with the signed foss APK (`hopp-X.Y.Z-foss.apk`, the
      name F-Droid's `Binaries` and Obtainium expect) — done by `scripts/release.sh`.
- [ ] **Zapstore:** `zsp publish zapstore.yaml` (docs/zapstore.md).
- [ ] **F-Droid:** merge request to fdroiddata with the updated metadata
      (first time: the inclusion request — open question 17 first).
- [ ] Tell the testers what changed — the crew (Signal) and the community
      (Telegram), from the changelog's highlights with the release link
      (docs/community-testing.md).

## Later: Google Play (not for the first release, ADR 0011 amended 2026-10-10)

- [ ] **Google Play:** upload the AAB to the closed test track first; the
      first release needs 12 testers for 14 days before production. Privacy
      policy URL https://hopp.sovereigncast.space/privacy/ and the data-safety form ("no data
      collected", ADR 0015).
- [ ] Privacy policy: merge branch `privacy-policy` of
      `nodesignal/hopp.sovereigncast.space` (responsible party filled in).
