#!/usr/bin/env bash
# Sign CI's unsigned release APK with the release key, publish it as a
# GitLab release and then on Zapstore (docs/release-checklist.md,
# docs/signing.md, docs/zapstore.md).
#
# Usage: [SIGN_WITH='bunker://…'] scripts/release.sh v0.1.0 /path/to/release-keystore [key-alias]
#
# SIGN_WITH (Nodesignal's Nostr key, an Amber bunker) lets zsp 2 publish on
# Zapstore right after the GitLab release; without it the script stops there
# and prints the command. zsp shows its preview before anything goes out;
# web/deploy/zapstore-images.mjs then puts the screenshots in order (Amber
# asks a second time).
#
# Runs on the maintainer's machine — the key never goes to CI (ADR 0017).
# apksigner asks for the keystore password itself; it is never on the command
# line. Signing CI's own APK, not a local build, keeps the published APK that
# build plus a signature — what F-Droid compares its build from source with.
#
# Steps: download the `release-unsigned` job's APK for the tag, sign it,
# check the certificate against the one in docs/signing.md, upload it to the
# project's generic package registry and create the release with the
# changelog's section as notes and the APK linked as hopp-X.Y.Z-foss.apk
# (the path F-Droid's `Binaries` and Obtainium expect).
set -euo pipefail

TAG=${1:?usage: scripts/release.sh vX.Y.Z keystore [alias]}
KEYSTORE=${2:?usage: scripts/release.sh vX.Y.Z keystore [alias]}
ALIAS=${3:-}
VERSION=${TAG#v}
PROJECT=nodesignal%2Fhopp
CERT_SHA256=c80fdec5f98a0aaf4954405e3c52c17a78db40f7bd05d2ac2585324003dca3d1
NAME=hopp-$VERSION-foss.apk

[[ $TAG =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "not a release tag: $TAG" >&2; exit 1; }
[[ -f $KEYSTORE ]] || { echo "no keystore at $KEYSTORE" >&2; exit 1; }
BUILD_TOOLS=$(ls -d "${ANDROID_HOME:?ANDROID_HOME is not set}"/build-tools/* | sort -V | tail -1)
cd "$(git rev-parse --show-toplevel)"
grep -q "^## \[$VERSION\]" CHANGELOG.md || { echo "CHANGELOG.md has no section for $VERSION" >&2; exit 1; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "→ CI's unsigned APK for $TAG"
glab api "projects/$PROJECT/jobs/artifacts/$TAG/raw/androidApp/build/outputs/apk/foss/release/hopp-$VERSION-foss-release-unsigned.apk?job=release-unsigned" > "$WORK/unsigned.apk"
"$BUILD_TOOLS/zipalign" -c 4 "$WORK/unsigned.apk"

echo "→ signing (apksigner asks for the password)"
# --alignment-preserved: apksigner otherwise re-aligns entries (the native
# library to 16 KB pages), and F-Droid could not copy our signature onto its
# own build (docs/fdroid.md). --layout below makes sure nothing moved.
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" ${ALIAS:+--ks-key-alias "$ALIAS"} --alignment-preserved true --out "$WORK/$NAME" "$WORK/unsigned.apk"
SIGNED=$("$BUILD_TOOLS/apksigner" verify --print-certs "$WORK/$NAME" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)
[[ $SIGNED == "$CERT_SHA256" ]] || { echo "signed with $SIGNED, not the release key $CERT_SHA256" >&2; exit 1; }
python3 scripts/compare_apks.py --layout "$WORK/unsigned.apk" "$WORK/$NAME"
cp "$WORK/$NAME" "./$NAME"
echo "✓ $NAME signed with the release key (also kept here, for Zapstore)"

echo "→ upload and release"
glab api --method PUT "projects/$PROJECT/packages/generic/hopp/$VERSION/$NAME" --input "$WORK/$NAME" > /dev/null
URL="https://gitlab.com/api/v4/projects/$PROJECT/packages/generic/hopp/$VERSION/$NAME"
awk -v v="$VERSION" '$0 ~ "^## \\[" v "\\]" {on=1; next} on && /^## \[/ {exit} on' CHANGELOG.md > "$WORK/notes.md"
printf '\n---\nSHA-256 of the signing certificate: `%s` (docs/signing.md).\n' "$CERT_SHA256" >> "$WORK/notes.md"
glab release create "$TAG" -R nodesignal/hopp --name "Hopp $VERSION" -F "$WORK/notes.md" \
    --assets-links "[{\"name\":\"$NAME\",\"url\":\"$URL\",\"direct_asset_path\":\"/$NAME\",\"link_type\":\"package\"}]"
echo "✓ https://gitlab.com/nodesignal/hopp/-/releases/$TAG"
echo "  download: https://gitlab.com/nodesignal/hopp/-/releases/$TAG/downloads/$NAME"

echo "→ Zapstore"
# zsp 2 takes the APK from the GitLab release just made (zapstore.yaml's
# `repository`); glab's token keeps GitLab's API from rate-limiting it, and
# zsp sends it to gitlab.com only.
ZSP_VERSION=$(zsp --version 2>/dev/null | tail -1 || true)
[[ $ZSP_VERSION == v2.* ]] || { echo "! zsp 2 not found ($ZSP_VERSION) — publish on Zapstore by hand (docs/zapstore.md)"; exit 0; }
export GITLAB_TOKEN=${GITLAB_TOKEN:-$(glab config get token --host gitlab.com 2>/dev/null || true)}
CHECK=$(zsp check --json zapstore.yaml || true)
FOUND=$(python3 -c 'import json, sys; print((json.load(sys.stdin).get("apk") or {}).get("version_name", ""))' <<<"$CHECK" 2>/dev/null || true)
[[ $FOUND == "$VERSION" ]] || { echo "zsp finds ${FOUND:-no APK}, not $VERSION, in the GitLab release:" >&2; echo "$CHECK" >&2; exit 1; }
if [[ -z ${SIGN_WITH:-} ]]; then
    echo "! SIGN_WITH is not set — not published on Zapstore. Then:"
    echo "  SIGN_WITH='bunker://…' zsp publish --no-compress zapstore.yaml"
    echo "  SIGN_WITH='bunker://…' node web/deploy/zapstore-images.mjs"
    exit 0
fi
# --no-compress: the screenshots go up as they are, so zapstore-images.mjs can tell them apart by
# their SHA-256 and put them in zapstore.yaml's order — zsp sorts them by address (docs/zapstore.md).
zsp publish --no-compress zapstore.yaml
echo "✓ Zapstore: https://zapstore.dev/apps/space.sovereigncast.hopp"
if command -v node > /dev/null && [[ -d web/deploy/node_modules ]]; then
    node web/deploy/zapstore-images.mjs
else
    echo "! node or web/deploy/node_modules missing ((cd web/deploy && npm ci)) — then:"
    echo "  SIGN_WITH='bunker://…' node web/deploy/zapstore-images.mjs"
fi
