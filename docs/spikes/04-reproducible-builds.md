# Spike 4 — reproducible builds and the F-Droid recipe

- **Date:** 2026-10-07
- **Question (ADR 0011, 0017):** does the Android build reproduce byte for
  byte, so F-Droid can publish *our* signed APK (one key everywhere) and CI can
  check the maintainer's local build?
- **Answer: yes, on the same platform.** Two release builds of the `foss`
  flavour from two separate clones in different directories
  (`--no-build-cache --no-configuration-cache`) produced the **same file**
  (SHA-256 of the whole APK identical, 566 entries).

## What helps

- `dependenciesInfo { includeInApk = false }` — no Google-encrypted dependency
  block in the APK (also an F-Droid requirement).
- AGP normalises zip timestamps (1981-01-01) and order; the baseline profile
  (`assets/dexopt/baseline.prof`) came out identical.
- `META-INF/version-control-info.textproto` carries the Git commit — identical
  as long as everyone builds the same tag from a clean tree.

## Still to check

- **Across platforms:** the maintainer signs on an Apple-Silicon Mac, F-Droid
  builds on Debian amd64. aapt2 and the JDK differ by platform; the first real
  release is the test. The plan if they differ: sign the CI-built (Linux)
  unsigned APK locally instead of building on the Mac.
- **With R8:** release builds are not minified yet (20 MB APK). Turning on
  R8 for phase 1 needs this check again.
- **JDK:** the recipe installs OpenJDK 21 to match `ci/Dockerfile`.

## Tooling

- `scripts/compare_apks.py a.apk b.apk` — entry-by-entry comparison that
  ignores signature files.
- `apksigcopier compare signed.apk --unsigned unsigned.apk` — what F-Droid
  runs; to use once a signed release exists.
- CI job `release-unsigned` (on `v*` tags): unsigned `foss` APK and `play`
  bundle as permanent artifacts.
- `fdroid/space.sovereigncast.hopp.yml` — draft metadata for the inclusion
  request.

## Blocker for F-Droid

The repository is private. F-Droid builds from public source, so it has to be
made public before the inclusion request.
