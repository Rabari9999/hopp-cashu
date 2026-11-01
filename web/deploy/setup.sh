#!/usr/bin/env bash
# Sets up Hopp Web's server once (ADR 0022, 5–6), on a fresh Debian 13, as root — and again
# at any time: every step only brings the machine to the state described here.
#
#   web/deploy/deploy.sh <server> --setup
#
# deploy.sh copies the server files (Caddyfile, the proxy's units) to /tmp/hopp-deploy first and runs
# this as root, then puts the client and the proxy on it. Run alone, it stops before it changes anything.
#
# What it does: updates, security updates by themselves, a firewall (SSH, HTTP, HTTPS), SSH by
# key only, Caddy from its own repository, the feed proxy's socket and service, and a user
# "deploy" that may only write the site's files. Nothing about listeners is stored here.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
for f in Caddyfile hopp-feed-proxy.socket hopp-feed-proxy.service; do
    [[ -f /tmp/hopp-deploy/$f ]] || { echo "no /tmp/hopp-deploy/$f: run web/deploy/deploy.sh <server> --setup" >&2; exit 1; }
done
. /etc/os-release
[[ $ID == debian && ${VERSION_ID%%.*} -ge 13 ]] || { echo "expects Debian 13 or later, found $PRETTY_NAME" >&2; exit 1; }

echo "== packages"
apt-get update -q
apt-get upgrade -yq
apt-get install -yq --no-install-recommends unattended-upgrades nftables rsync curl ca-certificates gnupg debian-keyring debian-archive-keyring apt-transport-https

echo "== security updates by themselves, a reboot at 04:30 when one needs it"
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
EOF
cat > /etc/apt/apt.conf.d/52hopp-unattended <<'EOF'
Unattended-Upgrade::Origins-Pattern { "origin=Debian,codename=${distro_codename}-security"; "origin=Debian,codename=${distro_codename}-updates"; "site=dl.cloudsmith.io"; };
Unattended-Upgrade::Automatic-Reboot "true";
Unattended-Upgrade::Automatic-Reboot-Time "04:30";
EOF

echo "== firewall: SSH, HTTP, HTTPS (and HTTP/3)"
cat > /etc/nftables.conf <<'EOF'
#!/usr/sbin/nft -f
flush ruleset
table inet filter {
	chain input {
		type filter hook input priority 0; policy drop;
		ct state established,related accept
		ct state invalid drop
		iif lo accept
		meta l4proto { icmp, ipv6-icmp } accept
		tcp dport { 22, 80, 443 } accept
		udp dport 443 accept
	}
	chain forward { type filter hook forward priority 0; policy drop; }
	chain output { type filter hook output priority 0; policy accept; }
}
EOF
systemctl enable --now nftables
nft -f /etc/nftables.conf

echo "== SSH: keys only"
cat > /etc/ssh/sshd_config.d/10-hopp.conf <<'EOF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
X11Forwarding no
AllowAgentForwarding no
EOF
sshd -t && systemctl reload ssh

echo "== Caddy"
if [[ ! -f /usr/share/keyrings/caddy-stable-archive-keyring.gpg ]]; then
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
    apt-get update -q
fi
apt-get install -yq caddy

echo "== the site's files, written by deploy only"
id deploy >/dev/null 2>&1 || useradd --create-home --shell /bin/bash deploy
install -d -m 700 -o deploy -g deploy /home/deploy/.ssh
install -m 600 -o deploy -g deploy /root/.ssh/authorized_keys /home/deploy/.ssh/authorized_keys
install -d -m 755 -o deploy -g deploy /srv/hopp-web /srv/hopp-web/releases
install -d -m 755 /opt/hopp-feed-proxy

echo "== the feed proxy"
# Its resolver must be one it may reach: with systemd-resolved the service reads resolved's
# upstream servers (hopp-feed-proxy.service, BindReadOnlyPaths) — public ones, not a stub on loopback.
seen=/etc/resolv.conf; [[ -f /run/systemd/resolve/resolv.conf ]] && seen=/run/systemd/resolve/resolv.conf
if grep -qE '^nameserver (127\.|::1|10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)' "$seen"; then
    echo "$seen names a local or private resolver — the proxy's IPAddressDeny would cut off its DNS" >&2
    exit 1
fi
# LLMNR answers names on the local network; nothing here needs it.
if systemctl is-active -q systemd-resolved; then
    mkdir -p /etc/systemd/resolved.conf.d
    printf '[Resolve]\nLLMNR=no\nMulticastDNS=no\n' > /etc/systemd/resolved.conf.d/10-hopp.conf
    systemctl restart systemd-resolved
fi
cp /tmp/hopp-deploy/hopp-feed-proxy.socket /tmp/hopp-deploy/hopp-feed-proxy.service /etc/systemd/system/
cp /tmp/hopp-deploy/Caddyfile /etc/caddy/Caddyfile
systemctl daemon-reload
systemctl enable --now hopp-feed-proxy.socket
caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
systemctl reload-or-restart caddy

echo "== done: $(caddy version | cut -d' ' -f1), $(nft list ruleset | grep -c dport) firewall rules"
