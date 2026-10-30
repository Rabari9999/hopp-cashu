#!/usr/bin/env bash
# Builds Hopp Web for its server (ADR 0022, 5): the client from a clean build, without source
# maps, pointed at the public feed proxy and compressed once (Brotli, gzip) for Caddy's
# precompressed file server; and the feed proxy as a static Linux binary.
#
#   web/deploy/build.sh [amd64|arm64]     → build/web-release/<version>/{site,proxy}
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
ARCH=${1:-amd64}
FEEDS=https://feeds.sovereigncast.space/
DIST=shared/build/dist/wasmJs/productionExecutable
VERSION="$(git describe --tags --always --dirty)"
OUT=build/web-release/$VERSION

# A clean distribution: webpack leaves the chunks of earlier builds behind.
rm -rf "$DIST" "$OUT"
./gradlew --no-daemon --console=plain -Phopp.web=true :shared:wasmJsBrowserDistribution
mkdir -p "$OUT/site"
rsync -a --exclude '*.map' "$DIST/" "$OUT/site/"
sed -i.bak "s|\(name=\"hopp-feed-proxy\" content=\"\)[^\"]*\"|\1$FEEDS\"|" "$OUT/site/index.html" && rm "$OUT/site/index.html.bak"
grep -q "content=\"$FEEDS\"" "$OUT/site/index.html" || { echo "index.html: no hopp-feed-proxy to set" >&2; exit 1; }
node web/deploy/pack.mjs "$OUT/site"

docker run --rm -v "$PWD/web/proxy":/src -v "$PWD/$OUT":/out -w /src golang:1.25-alpine \
    sh -c "go test -count=1 ./... && CGO_ENABLED=0 GOOS=linux GOARCH=$ARCH go build -trimpath -ldflags='-s -w' -o /out/proxy ."
chmod 755 "$OUT/proxy"
echo "$VERSION" > "$OUT/site/version.txt"
du -sh "$OUT/site" "$OUT/proxy"
echo "$OUT"
