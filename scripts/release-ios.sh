#!/usr/bin/env bash
# Build the iOS release for AltStore PAL and upload it to App Store Connect
# (docs/ios-release.md, ADR 0009).
#
# Usage: scripts/release-ios.sh vX.Y.Z [--no-upload]
#
# Runs on the releasing developer's Mac, with their team in
# iosApp/Configuration/Local.xcconfig and their Apple account signed in to
# Xcode (Settings → Accounts) — or an App Store Connect API key in
# ASC_KEY_PATH, ASC_KEY_ID and ASC_ISSUER_ID. Xcode creates the distribution
# certificate and profile itself (-allowProvisioningUpdates).
#
# Builds from the checked-out tag, so the upload is exactly that commit.
# Version and build number come from Version.xcconfig (Gradle's syncIosVersion,
# from androidApp/build.gradle.kts): every upload needs a higher versionCode.
# After the upload: notarization, ADP and the AltStore source by hand.
set -euo pipefail

TAG=${1:?usage: scripts/release-ios.sh vX.Y.Z [--no-upload]}
UPLOAD=1
[[ ${2:-} == --no-upload ]] && UPLOAD=0
VERSION=${TAG#v}
# The PAL build's own bundle ID (ADR 0004): space.sovereigncast.hopp stays
# free for a later App Store build in Nodesignal's own account.
BUNDLE_ID=space.sovereigncast.hopp.pal

[[ $TAG =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "not a release tag: $TAG" >&2; exit 1; }
cd "$(git rev-parse --show-toplevel)"
[[ $(xcode-select -p) == *Xcode*.app/* ]] || { echo "xcode-select points at $(xcode-select -p), not Xcode (docs/ios-release.md)" >&2; exit 1; }
[[ -z $(git status --porcelain) ]] || { echo "the working tree is not clean" >&2; exit 1; }
[[ $(git rev-parse HEAD) == $(git rev-parse "$TAG^{commit}") ]] || { echo "check out $TAG first (git checkout $TAG)" >&2; exit 1; }
grep -q "^## \[$VERSION\]" CHANGELOG.md || { echo "CHANGELOG.md has no section for $VERSION" >&2; exit 1; }
LOCAL=iosApp/Configuration/Local.xcconfig
TEAM_ID=$(sed -n 's/^[[:space:]]*TEAM_ID[[:space:]]*=[[:space:]]*\([A-Z0-9]*\).*/\1/p' "$LOCAL" 2>/dev/null | tail -1)
[[ -n $TEAM_ID && $TEAM_ID != ABCDE12345 ]] || { echo "no TEAM_ID of yours in $LOCAL (copy Local.xcconfig.example)" >&2; exit 1; }

# The tag's Version.xcconfig must already say this version: Gradle writes it
# on every Android build, and a stale one would upload the wrong numbers.
./gradlew -q --no-daemon :androidApp:syncIosVersion
[[ -z $(git status --porcelain) ]] || { echo "Version.xcconfig was out of date — commit it, tag again" >&2; git diff --stat >&2; exit 1; }
grep -q "^MARKETING_VERSION = $VERSION$" iosApp/Configuration/Version.xcconfig || { echo "Version.xcconfig is not $VERSION" >&2; exit 1; }
BUILD=$(sed -n 's/^CURRENT_PROJECT_VERSION = //p' iosApp/Configuration/Version.xcconfig)

AUTH=()
if [[ -n ${ASC_KEY_PATH:-} ]]; then
    AUTH=(-authenticationKeyPath "$ASC_KEY_PATH" -authenticationKeyID "${ASC_KEY_ID:?}" -authenticationKeyIssuerID "${ASC_ISSUER_ID:?}")
fi

OUT=build/ios/$VERSION
rm -rf "$OUT"
mkdir -p "$OUT"
ARCHIVE=$OUT/Hopp.xcarchive

echo "→ archive Hopp $VERSION ($BUILD) as $BUNDLE_ID for team $TEAM_ID"
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release \
    -destination 'generic/platform=iOS' -archivePath "$ARCHIVE" \
    -allowProvisioningUpdates ${AUTH[@]+"${AUTH[@]}"} \
    BUNDLE_ID="$BUNDLE_ID" TEAM_ID="$TEAM_ID" \
    archive > "$OUT/archive.log" || { tail -30 "$OUT/archive.log" >&2; echo "archive failed — $OUT/archive.log" >&2; exit 1; }

# app-store-connect: the method for every upload to App Store Connect,
# notarization included. Our own build number, not one Xcode makes up.
OPTIONS=$OUT/ExportOptions.plist
plutil -create xml1 "$OPTIONS"
plutil -insert method -string app-store-connect "$OPTIONS"
plutil -insert destination -string "$([[ $UPLOAD == 1 ]] && echo upload || echo export)" "$OPTIONS"
plutil -insert teamID -string "$TEAM_ID" "$OPTIONS"
plutil -insert signingStyle -string automatic "$OPTIONS"
plutil -insert manageAppVersionAndBuildNumber -bool NO "$OPTIONS"
plutil -insert uploadSymbols -bool YES "$OPTIONS"

echo "→ $([[ $UPLOAD == 1 ]] && echo 'sign and upload to App Store Connect' || echo 'sign and export the .ipa')"
xcodebuild -exportArchive -archivePath "$ARCHIVE" -exportOptionsPlist "$OPTIONS" -exportPath "$OUT/export" \
    -allowProvisioningUpdates ${AUTH[@]+"${AUTH[@]}"} > "$OUT/export.log" || { tail -30 "$OUT/export.log" >&2; echo "export failed — $OUT/export.log" >&2; exit 1; }

if [[ $UPLOAD == 1 ]]; then
    echo "✓ Hopp $VERSION ($BUILD) is in App Store Connect — next: export compliance, then submit for notarization (docs/ios-release.md)"
else
    echo "✓ $OUT/export/Hopp.ipa"
fi
