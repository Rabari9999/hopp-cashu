#!/usr/bin/env bash
# Hopp's web client, locally (ADR 0022, stage W2): builds it, starts the feed proxy in Docker
# (OrbStack) and serves the client on http://127.0.0.1:8642/. Ctrl-C stops both.
#
#   scripts/web-dev.sh            build, proxy, serve
#   scripts/web-dev.sh --no-build serve what was built last
#   … --lan                       on the LAN over HTTPS, for an iPhone (web/README.md): needs
#                                 an mkcert certificate in web/e2e/certs
#   … --strict                    with the server's headers (web/deploy/Caddyfile: CSP and the rest)
#
# Checks against it: cd web/e2e && npm ci && node run.mjs [--sync] (CHROME_BIN for a given Chrome).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
DIST=shared/build/dist/wasmJs/productionExecutable
PORT=8642
PROXY_PORT=8643

LAN=; BUILD=1; STRICT=
for a in "$@"; do case $a in --lan) LAN=--lan ;; --no-build) BUILD= ;; --strict) STRICT=--strict ;; esac; done
if [[ -n $BUILD ]]; then
    ./gradlew --console=plain -Phopp.web=true :shared:wasmJsBrowserDistribution
fi
[[ -f $DIST/index.html ]] || { echo "no build in $DIST" >&2; exit 1; }

docker build -q -t hopp-feed-proxy web/proxy > /dev/null
docker rm -f hopp-feed-proxy > /dev/null 2>&1 || true
docker run -d --rm --name hopp-feed-proxy -p 127.0.0.1:$PROXY_PORT:8643 \
    -e HOPP_PROXY_ORIGINS="http://127.0.0.1:$PORT,http://localhost:$PORT" hopp-feed-proxy > /dev/null
trap 'docker stop hopp-feed-proxy > /dev/null 2>&1 || true' EXIT
echo "feed proxy on http://127.0.0.1:$PROXY_PORT/ (index.html's hopp-feed-proxy)"

if [[ -n $LAN ]]; then
    echo "Hopp on https://$(ipconfig getifaddr en0 2>/dev/null || hostname):$PORT/"
else
    echo "Hopp on http://127.0.0.1:$PORT/"
fi
node web/e2e/serve.mjs "$PWD/$DIST" "$PORT" $LAN $STRICT
