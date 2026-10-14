# Hopp in F-Droid

- **Status:** open — paused 2026-10-14, everything in the repository is ready
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

## Steps left

1. Tag `v0.1.1` **on 318b7fb** (`git tag -a v0.1.1 318b7fb`), not on a later
   `main`: phase-2 features landed after it, and 0.1.1's changelog says the app
   did not change. CI builds `release-unsigned` (~5 min).
2. Maintainer: `scripts/release.sh v0.1.1 ~/Documents/hopp-release.jks`, then
   `zsp publish zapstore.yaml` (Amber bunker, docs/zapstore.md).
3. Check the signed APK once more with F-Droid's tools: build v0.1.1 in
   `buildserver-trixie`, `apksigcopier compare` against the release APK
   (docs/fdroid.md, Reproducibility).
4. Fork fdroiddata, add `metadata/space.sovereigncast.hopp.yml`, open the
   merge request ("App inclusion" template) under the maintainer's account —
   ask before sending.
5. Answer the reviewers; after the merge, the next build cycle publishes it.

## Done when

Hopp is listed on f-droid.org with our signature (the APK's certificate is
`c80fdec5…a3d1`, docs/signing.md).
