#!/usr/bin/env bash
# Puts a build of Hopp Web on its server (ADR 0022, 5): web/deploy/build.sh first.
#
#   web/deploy/deploy.sh <server> [build/web-release/<version>]   the newest build when none is given
#   web/deploy/deploy.sh <server> --setup                           also (re)runs setup.sh as root first
#   web/deploy/deploy.sh <server> --dev [build/web-dev/<version>]   a --dev build on play-dev., nothing else
#
# The site goes into releases/<version> and current switches to it at once; the last five stay
# for going back (ln -sfn releases/<older> /srv/hopp-web/current). The proxy is replaced and
# restarted only when it changed.
#
# play. takes only a release: a build of an exact tag — vX.Y.Z, or vX.Y.Z.N for a fix of Hopp Web
# alone. play-dev. takes only --dev builds, and such a deploy leaves the proxy and the server files
# alone — both addresses share them (ADR 0022, 15).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
HOST=${1:?server address}; shift
KEY=${HOPP_WEB_KEY:-$HOME/.ssh/hopp-web-vps}
SSH=(ssh -i "$KEY" -o IdentitiesOnly=yes)
SETUP=; REL=; DEV=
for a in "$@"; do case $a in --setup) SETUP=1 ;; --dev) DEV=1 ;; *) REL=$a ;; esac; done
[[ -z $SETUP || -z $DEV ]] || { echo "--setup is for play., not with --dev" >&2; exit 1; }
if [[ -n $DEV ]]; then BUILDS=build/web-dev SITE=/srv/hopp-web-dev NAME=play-dev; else BUILDS=build/web-release SITE=/srv/hopp-web NAME=play; fi
REL=${REL:-$(ls -td "$BUILDS"/*/ | head -1)}
REL=${REL%/}
VERSION=$(basename "$REL")
[[ -f $REL/site/index.html ]] || { echo "no build in $REL" >&2; exit 1; }
if [[ -n $DEV ]]; then
    [[ $VERSION == *-dev ]] || { echo "$VERSION is no --dev build (web/deploy/build.sh --dev)" >&2; exit 1; }
else
    [[ $VERSION =~ ^v[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?$ ]] || { echo "$VERSION is no release: play. takes only a build of an exact tag, others --dev on play-dev." >&2; exit 1; }
    [[ -x $REL/proxy ]] || { echo "no proxy in $REL" >&2; exit 1; }

    # Server files travel with every deploy, so the server always runs what the repository says.
    # scp, not rsync: a fresh server has no rsync before setup.sh.
    "${SSH[@]}" "root@$HOST" 'install -d -m 700 /tmp/hopp-deploy'
    scp -q -i "$KEY" -o IdentitiesOnly=yes web/deploy/Caddyfile web/deploy/hopp-feed-proxy.socket web/deploy/hopp-feed-proxy.service "root@$HOST:/tmp/hopp-deploy/"
    if [[ -n $SETUP ]]; then
        "${SSH[@]}" "root@$HOST" 'bash -s' < web/deploy/setup.sh
    fi
fi

echo "== site $VERSION"
rsync -a --delete -e "${SSH[*]}" "$REL/site/" "deploy@$HOST:$SITE/releases/$VERSION/"
"${SSH[@]}" "deploy@$HOST" "set -e; cd $SITE
    ln -sfn releases/$VERSION current.new && mv -T current.new current
    ls -td releases/*/ | tail -n +6 | xargs -r rm -rf"
if [[ -n $DEV ]]; then
    echo "== live: https://$NAME.sovereigncast.space/version.txt → $VERSION"
    exit 0
fi

echo "== proxy and server files"
rsync -a -e "${SSH[*]}" "$REL/proxy" "root@$HOST:/tmp/hopp-deploy/proxy"
"${SSH[@]}" "root@$HOST" 'set -e; cd /tmp/hopp-deploy
    install -d -m 755 -o deploy -g deploy /srv/hopp-web-dev /srv/hopp-web-dev/releases
    changed=
    cmp -s proxy /opt/hopp-feed-proxy/proxy || { install -m 755 proxy /opt/hopp-feed-proxy/proxy; changed=1; }
    for u in hopp-feed-proxy.socket hopp-feed-proxy.service; do cmp -s $u /etc/systemd/system/$u || { cp $u /etc/systemd/system/; changed=1; }; done
    systemctl daemon-reload
    # The running proxy holds the old binary or unit: stopped, the socket restarted (a changed .socket
    # takes effect only so), and the next request starts the new one. A failure here fails the deploy.
    if [ -n "$changed" ]; then
        systemctl stop hopp-feed-proxy.service
        systemctl restart hopp-feed-proxy.socket
    fi
    systemctl is-active -q hopp-feed-proxy.socket || { echo "the proxy socket is not active" >&2; exit 1; }
    cmp -s Caddyfile /etc/caddy/Caddyfile || { caddy validate --config Caddyfile --adapter caddyfile && cp Caddyfile /etc/caddy/Caddyfile && systemctl reload caddy; }
    echo "proxy: $(systemctl is-active hopp-feed-proxy.socket) socket, $([ -n "$changed" ] && echo replaced || echo unchanged)"'
echo "== live: https://$NAME.sovereigncast.space/version.txt → $VERSION"
