# Hopp in F-Droid

- **Status:** in progress — 0.1.1 released and checked 2026-10-15; merge request waits for the maintainer's go
- **ADR:** 0011 (distribution, one key everywhere), 0017 (release checklist)
- **How:** docs/fdroid.md

## Why

F-Droid is one of the two first release channels (ADR 0011). It must publish
our signed APK, so that installs from F-Droid, Zapstore and Obtainium update
each other.

## Where it stands (2026-10-14)

- v0.1.0 builds identically in F-Droid's build image, but its released APK
  was signed with re-aligned entries; F-Droid's `apksigcopier` check fails on
  it. 0.1.0 can not go to F-Droid.
- Fixed on `main` (2226293): `release.sh` signs with `--alignment-preserved
  true` and checks the layout; fastlane texts and images; F-Droid metadata
  (category Podcast, `UpdateCheckData`, first build 0.1.1) passes `fdroid lint`.
- 0.1.1 (versionCode 2) is prepared on `main`, **not tagged**. Nothing in the
  app changed — only signing and store texts.
- 2026-10-15: plan changed (maintainer) — 0.1.1 is released from the current
  `main` with everything since 0.1.0 (changelog updated), not from 318b7fb.

## Steps left

1. ~~Tag `v0.1.1` on `main`~~ — done 2026-10-15 (7dde033).
2. ~~`scripts/release.sh v0.1.1`, `zsp publish`~~ — done 2026-10-15; Zapstore's
   relay holds 0.1.1 with the APK's hash.
3. ~~Check with F-Droid's tools~~ — done 2026-10-15: v0.1.1 built in
   `buildserver-trixie` is identical to CI's (240 entries), and
   `apksigcopier compare` against the released APK matches. `fdroid lint`
   passes; `commit` is the full hash, as fdroiddata asks.
4. Fork fdroiddata, add `metadata/space.sovereigncast.hopp.yml`, open the
   merge request ("App inclusion" template, title "New app: Hopp") under the
   maintainer's account — ask before sending.
5. Answer the reviewers; after the merge, the next build cycle publishes it.

## Done when

Hopp is listed on f-droid.org with our signature (the APK's certificate is
`c80fdec5…a3d1`, docs/signing.md).
