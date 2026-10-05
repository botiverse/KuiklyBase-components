#!/usr/bin/env bash
# Disposable Hosted runner only. No product resolver, proxy or TLS changes.
set -euo pipefail
out="${1:?evidence directory}"
mkdir -p "$out"
python3 - "$out" <<'PY'
import ipaddress, pathlib, socket, sys
out = pathlib.Path(sys.argv[1])
addresses = sorted({row[4][0] for row in socket.getaddrinfo('cloudflare-quic.com', 443, socket.AF_INET, socket.SOCK_STREAM)})
if not addresses:
    raise SystemExit('No public IPv4 address for the H3 fixture')
selected = str(ipaddress.IPv4Address(addresses[0]))
(out / 'ipv4.txt').write_text(selected + '\n')
(out / 'public-ipv4-answers.txt').write_text('\n'.join(addresses) + '\n')
PY
ipv4="$(cat "$out/ipv4.txt")"
# dnsmasq-base has no service. Keep the host system resolver untouched; only the
# positive emulator opts into this loopback DNS listener using -dns-server.
sudo apt-get update -qq
sudo apt-get install -y --no-install-recommends dnsmasq-base dnsutils
/usr/sbin/dnsmasq --version > "$out/dnsmasq-version.txt"
sudo /usr/sbin/dnsmasq --conf-file=/dev/null --no-hosts --bind-interfaces \
  --listen-address=127.0.0.1 --port=53 --cache-size=0 \
  --host-record="cloudflare-quic.com,$ipv4" --local=/cloudflare-quic.com/ \
  --log-queries --log-facility="$out/dnsmasq.log" --pid-file="$out/dnsmasq.pid"
dig @127.0.0.1 cloudflare-quic.com A > "$out/dns-a.txt"
dig @127.0.0.1 cloudflare-quic.com AAAA > "$out/dns-aaaa.txt"
test "$(dig @127.0.0.1 +short cloudflare-quic.com A)" = "$ipv4"
test -z "$(dig @127.0.0.1 +short cloudflare-quic.com AAAA)"
printf 'H3_EXPECTED_IPV4=%s\n' "$ipv4" >> "${GITHUB_ENV:?Hosted environment required}"
