#!/usr/bin/env bash
# Builds Hopp Web for its server (ADR 0022, 5): the client from a clean build, without source
# maps, pointed at the public feed proxy and compressed once (Brotli, gzip) for Caddy's
# precompressed file server; and the feed proxy as a static Linux binary.
#
#   web/deploy/build.sh [amd64|arm64]     → build/web-release/<version>/{site,proxy}
#   web/deploy/build.sh --dev             → build/web-dev/<version>-dev/site, named Hopp Dev
#
# Then: SIGN_WITH='bunker://…' node web/deploy/sign.mjs build/web-release/<version>/site,
# web/deploy/deploy.sh, node web/deploy/verify.mjs. A --dev build is not signed and needs no proxy:
# web/deploy/deploy.sh <server> --dev puts it on play-dev. (ADR 0022, 15).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
ARCH=amd64; DEV=
for a in "$@"; do case $a in --dev) DEV=1 ;; *) ARCH=$a ;; esac; done
FEEDS=https://feeds.sovereigncast.space/
DIST=shared/build/dist/wasmJs/productionExecutable
VERSION="$(git describe --tags --always --dirty)"
OUT=build/web-release/$VERSION
if [[ -n $DEV ]]; then VERSION=$VERSION-dev; OUT=build/web-dev/$VERSION; fi

# A clean distribution: webpack leaves the chunks of earlier builds behind.
rm -rf "$DIST" "$OUT"
./gradlew --no-daemon --console=plain -Phopp.web=true :shared:wasmJsBrowserDistribution
mkdir -p "$OUT/site"
rsync -a --exclude '*.map' "$DIST/" "$OUT/site/"
sed -i.bak "s|\(name=\"hopp-feed-proxy\" content=\"\)[^\"]*\"|\1$FEEDS\"|" "$OUT/site/index.html" && rm "$OUT/site/index.html.bak"
grep -q "content=\"$FEEDS\"" "$OUT/site/index.html" || { echo "index.html: no hopp-feed-proxy to set" >&2; exit 1; }
# Which of the two it is: the name and icons in the tab and on the home screen, a mark over the
# app (web/deploy/dev/). All of it around the app: its code is what a release's build holds.
if [[ -n $DEV ]]; then
    sed -i.bak 's|<title>[^<]*</title>|<title>Hopp Dev</title>|' "$OUT/site/index.html" && rm "$OUT/site/index.html.bak"
    sed -i.bak -E 's|("(short_)?name": )"Hopp"|\1"Hopp Dev"|' "$OUT/site/manifest.webmanifest" && rm "$OUT/site/manifest.webmanifest.bak"
    cp web/deploy/dev/* "$OUT/site/"
    sed -i.bak "s|__VERSION__|${VERSION%-dev}|" "$OUT/site/dev.css" && rm "$OUT/site/dev.css.bak"
    sed -i.bak 's|<link rel="stylesheet" href="splash.css">|&<link rel="stylesheet" href="dev.css">|' "$OUT/site/index.html" && rm "$OUT/site/index.html.bak"
    grep -q '<title>Hopp Dev</title>' "$OUT/site/index.html" && grep -q 'href="dev.css"' "$OUT/site/index.html" && grep -q '"short_name": "Hopp Dev"' "$OUT/site/manifest.webmanifest" || { echo "not marked as Hopp Dev" >&2; exit 1; }
fi
# No folder of this machine in what is published (shared/webpack.config.d/no-build-paths.js).
if grep -rlE "file:///(Users|home)/|$PWD" "$OUT/site"; then echo "the files above name a folder of this machine" >&2; exit 1; fi
echo "$VERSION" > "$OUT/site/version.txt"
# The build's file list and its hash (ADR 0022, 14): signed with web/deploy/sign.mjs, shown in
# Hopp, checked by web/deploy/verify.mjs. Before compressing, so the copies are not in it.
node web/deploy/release.mjs "$OUT/site" "$VERSION"
node web/deploy/pack.mjs "$OUT/site"

if [[ -z $DEV ]]; then
    docker run --rm -v "$PWD/web/proxy":/src -v "$PWD/$OUT":/out -w /src golang:1.25-alpine \
        sh -c "go test -count=1 ./... && CGO_ENABLED=0 GOOS=linux GOARCH=$ARCH go build -trimpath -ldflags='-s -w' -o /out/proxy ."
    chmod 755 "$OUT/proxy"
    du -sh "$OUT/proxy"
fi
du -sh "$OUT/site"
echo "$OUT"
