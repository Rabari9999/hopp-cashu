# iOS release (AltStore PAL)

Hopp for iOS ships through **AltStore PAL** first (ADR 0009, amended
2026-10-16): Apple notarizes it instead of reviewing it, in the EU, Japan and
Brazil. The upload and the notarization run in the **releasing developer's**
Apple account — for now a befriended developer's, not Nodesignal's. The
maintainer tags the release, the developer builds and uploads it.

The PAL build's bundle ID is **`space.sovereigncast.hopp.pal`** (ADR 0004).
Do not register `space.sovereigncast.hopp` in your team — an explicit bundle
ID belongs to the first team that registers it, and that one is kept for an
App Store build in Nodesignal's own account. `scripts/release-ios.sh` sets
the `.pal` ID itself; archive only through it.

## Once: the Mac

1. Xcode, opened once (licence, components); Kotlin/Native needs the full
   Xcode, not the Command Line Tools:
   `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer`.
2. JDK 21 (`brew install --cask temurin@21`) and the Android SDK, with
   `sdk.dir=…` in `local.properties` in the repository root — Gradle sets up
   the Android targets even for an iOS build.
3. `git clone https://gitlab.com/nodesignal/hopp.git`, then
   `cp iosApp/Configuration/Local.xcconfig.example iosApp/Configuration/Local.xcconfig`
   and put your Team ID in. Git ignores that file.
4. Xcode → Settings → Accounts: sign in with your Apple account. Xcode then
   creates the distribution certificate and profile at the first release.
5. Check: `./gradlew :shared:linkDebugFrameworkIosSimulatorArm64` (the first
   run downloads Kotlin/Native, 10–20 min), then open
   `iosApp/iosApp.xcodeproj` and run it in a simulator. On a real iPhone set
   your own `BUNDLE_ID` in `Local.xcconfig` (see the example).

Linking the release framework takes about 6 GB of Java heap (set in the Xcode
build phase); 16 GB RAM in the Mac is comfortable.

## Once: Apple and AltStore

1. **EU terms.** The Account Holder signs the Alternative EU Terms Addendum
   (developer.apple.com → Account → Agreements); for Japan and Brazil the
   current Developer Program License Agreement is enough.
2. **App record** in App Store Connect → Apps → New App: iOS, name Hopp,
   primary language English (U.S.) or German, bundle ID
   `space.sovereigncast.hopp.pal` (register it first under Certificates,
   Identifiers & Profiles → Identifiers, no capabilities), SKU `hopp-pal`.
   Then: privacy policy https://hopp.sovereigncast.space/privacy/, age rating,
   App Privacy "Data Not Collected" (ADR 0015).
3. **AltStore PAL** (https://faq.altstore.io/developers/distribute-with-altstore-pal):
   register your developer ID through AltStore's REST API — the ID from App
   Store Connect → Edit Profile (the profile's UUID, not the Team ID). Enter
   the token you get under Users and Access → Integrations → Marketplace,
   select Hopp, choose "Yes, send notifications".
4. **App Store Connect API key** (Users and Access → Integrations → App Store
   Connect API, role App Manager): downloading the Alternative Distribution
   Package (ADP) needs it. `release-ios.sh` can use it too, instead of the
   Xcode account: `ASC_KEY_PATH`, `ASC_KEY_ID`, `ASC_ISSUER_ID`.
5. **Export compliance**, at the first upload (App Store Connect → the build).
   Hopp implements standard encryption itself (ChaCha20, NIP-44 for NWC and
   signers, NIP-49 keys), so `Info.plist` says
   `ITSAppUsesNonExemptEncryption = true` and the answers are: encryption yes;
   an exemption no; proprietary algorithms no; standard algorithms beyond the
   OS's yes; France yes — which asks for the French encryption declaration
   (ANSSI). Once Apple accepts the documents it gives a code; put it in
   `Info.plist` as `ITSEncryptionExportComplianceCode` (a commit on `main`),
   and later uploads stop asking.

## Every release

The maintainer:

1. Raise `versionName` and `versionCode` in `androidApp/build.gradle.kts`, run
   any Android build (Gradle writes `iosApp/Configuration/Version.xcconfig`),
   add the `CHANGELOG.md` section, commit, tag `vX.Y.Z`, push.
   Every upload to App Store Connect needs a new build number — after a
   refused notarization, too: then the next `versionCode`.

The releasing developer:

2. `git fetch --tags && git checkout vX.Y.Z && scripts/release-ios.sh vX.Y.Z`
   (archive, sign, upload; `--no-upload` only exports the `.ipa`).
3. App Store Connect: a new version X.Y.Z with that build; App Review →
   Review Type → **Notarization**; Submit.
4. After the notarization AltStore PAL processes the build by itself. Download
   the ADP with the API key (Apple: "Distributing apps in the European Union"
   in the App Store Connect help).
5. Host the ADP **unchanged** — directory structure, file hashes and
   `manifest.json` as they came. Default place: Hopp's website
   (`nodesignal/hopp.sovereigncast.space`, GitLab Pages) under
   `/altstore/X.Y.Z/`; the maintainer pushes it there.
6. Add the version at the top of `versions` in `altstore/source.json`
   (`version`, `buildVersion`, `date`, `downloadURL` = the ADP's
   `manifest.json`, `size` = the size in bytes of one file in the ADP's
   `variant` folder, the changelog as `localizedDescription`) and publish the
   file as https://hopp.sovereigncast.space/altstore/source.json.
   `marketplaceID` is the app's Apple ID (App Store Connect → App
   Information). `appPermissions.entitlements` must list the signed app's
   entitlements except `application-identifier` and the team identifier —
   `codesign -d --entitlements - Hopp.app` on the `.ipa`'s app — or AltStore
   refuses to install it. Once, next to it: the app icon
   (`iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png`) as
   `/altstore/icon.png`, and iPhone screenshots (393 × 852 or the same
   ratio) in `screenshots`.
7. On an iPhone in the EU (iOS 17.4 or later): AltStore PAL → Sources → add
   the source's URL, install, open, play an episode.
