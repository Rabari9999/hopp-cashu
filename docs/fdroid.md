# Submitting Hopp to F-Droid

F-Droid builds Hopp itself from the tagged source on its Debian build server.
With `Binaries` and `AllowedAPKSigningKeys` in the metadata it then compares
its build with our signed APK from the GitLab release: if they match byte for
byte outside the signature, F-Droid publishes **our** APK (ADR 0011: one key
everywhere, updates between F-Droid, Zapstore and Obtainium just work). If
not, the build fails — it never falls back to its own key silently.

## What is in this repository

- `fdroid/space.sovereigncast.hopp.yml` — the metadata file for fdroiddata:
  build recipe (`foss` flavour, JDK 21), `Binaries`, `AllowedAPKSigningKeys`,
  update check by tag. `UpdateCheckData` reads the version from
  `build.gradle.kts`, where `versionName` is a variable.
- `fastlane/metadata/android/` — title, short and full description, release
  notes per versionCode (`changelogs/<versionCode>.txt`), icon and
  screenshots, in English and German. F-Droid (and Zapstore) read them from
  the tag.

## The inclusion request (once)

1. Fork https://gitlab.com/fdroid/fdroiddata on GitLab, new branch
   `space.sovereigncast.hopp`.
2. Copy `fdroid/space.sovereigncast.hopp.yml` to
   `metadata/space.sovereigncast.hopp.yml` there, without our header comment.
3. Check it with fdroidserver, in the fdroiddata clone (it needs the
   `config/` there; nothing to install, the image's entry point is `fdroid`):

   ```bash
   docker run --rm -v "$PWD":/repo -w /repo registry.gitlab.com/fdroid/docker-executable-fdroidserver:master lint space.sovereigncast.hopp
   docker run --rm -v "$PWD":/repo -w /repo registry.gitlab.com/fdroid/docker-executable-fdroidserver:master rewritemeta space.sovereigncast.hopp
   ```

   Both passed on 2026-10-14 (rewritemeta changed nothing).

4. Push and open the merge request with the "App inclusion" template; tick
   its boxes (we are the authors; MIT; no non-free dependencies in `foss`).
   fdroiddata's CI runs `fdroid build` on it — the same comparison with our
   APK as later on the build server.
5. Answer the reviewers. After the merge the app appears with the next
   build cycle of the build server, usually within a few days.

## Every release after that

Nothing to do in fdroiddata: `AutoUpdateMode: Version` and
`UpdateCheckMode: Tags` make F-Droid pick up each `v*` tag, add the build
and compare it with the `hopp-X.Y.Z-foss.apk` of that release. What must be
right for that (docs/release-checklist.md):

- `versionCode` +1 and `appVersion` in `androidApp/build.gradle.kts`;
- `fastlane/metadata/android/*/changelogs/<versionCode>.txt` (≤ 500 characters);
- the signed APK on the GitLab release before F-Droid's build server gets
  there (`scripts/release.sh` right after the tag).

## Reproducibility

The build has to come out byte-identical on F-Droid's server. Checked
2026-10-14 for v0.1.0 in F-Droid's `buildserver-trixie` image: the unsigned
APK there was **identical** to CI's (all 220 entries). But F-Droid's actual
check, `apksigcopier compare`, copies our signature onto its build — and
that **failed** for the released 0.1.0: `apksigner` had re-aligned 83 entries
while signing (the native library to 16 KB pages), so the signature covered
a different layout. Since 0.1.1, `scripts/release.sh` signs with
`--alignment-preserved true` and refuses an APK whose entries moved
(`compare_apks.py --layout`); with that, `apksigcopier compare` matches.
Hence F-Droid starts with 0.1.1, not 0.1.0.

 Check with
F-Droid's own build image before a release that changes the build (Gradle,
AGP, Kotlin, JDK, R8 rules):

The image already ships JDK 21, so the metadata needs no `sudo` block
(fdroiddata review, 2026-10-15).

```bash
git clone --branch vX.Y.Z https://gitlab.com/nodesignal/hopp.git
docker run --rm --platform linux/amd64 -v "$PWD":/work registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie bash -c '
  cd /work/hopp &&
  echo sdk.dir=/opt/android-sdk > local.properties && ./gradlew -q :androidApp:assembleFossRelease'
python3 scripts/compare_apks.py --layout hopp-X.Y.Z-foss.apk hopp/androidApp/build/outputs/apk/foss/release/hopp-X.Y.Z-foss-release-unsigned.apk
# what F-Droid runs (apksigcopier from apt or pip; apksigner on PATH):
apksigcopier compare hopp-X.Y.Z-foss.apk --unsigned hopp/androidApp/build/outputs/apk/foss/release/hopp-X.Y.Z-foss-release-unsigned.apk
```
