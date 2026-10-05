#!/usr/bin/env bash
# Hosted Linux only: build a small probe, retain exact committed .so, and export
# the same independent curl rootfs for host/guest comparisons (no product rebuild).
set -euo pipefail
out="${1:?output directory}"
mkdir -p "$out"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ndk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Android SDK path}}/ndk/28.0.13004108"
"$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android23-clang" \
  -std=gnu11 -Wall -Wextra -Werror -I "$root/ohosApp/pbcurlwrapper/src/main/cpp/include" \
  "$root/scripts/probe-android-http3.c" -ldl -o "$out/probe"
cp "$root/network/libs/android/x86_64/libnetworkkmmcurl.so" "$out/"
cp "$root/network/src/androidTest/assets/networkkmm-cacert.pem" "$out/ca.pem"
image='debian:trixie-slim@sha256:a99cfc517144bc59b1978475ec53b46ecabec7e43635402ee5b77cc54cd1b20a'
container="$(docker create "$image" bash -c 'apt-get update -qq && apt-get install -y -qq --no-install-recommends curl ca-certificates && rm -rf /var/lib/apt/lists/*')"
trap 'docker rm -f "$container" >/dev/null 2>&1 || true' EXIT
docker start -a "$container" > "$out/reference-setup.log" 2>&1
test "$(docker inspect -f '{{.State.ExitCode}}' "$container")" = 0
getent ahostsv4 cloudflare-quic.com > "$out/host-addresses.txt"
awk 'NR==1 {print $1}' "$out/host-addresses.txt" > "$out/address.txt"
docker cp "$out/ca.pem" "$container:/tmp/diagnostic-ca.pem"
# Export after CA injection. Host control uses the identical rootfs/userspace.
docker export "$container" > "$out/reference-root.tar"
reference_image="$(docker import "$out/reference-root.tar")"
printf '%s\n' "$image" "$reference_image" > "$out/reference-image.txt"
docker run --rm "$reference_image" curl --version > "$out/reference-version.txt"
docker run --rm "$reference_image" dpkg-query -W > "$out/reference-packages.txt"
set +e
docker run --rm "$reference_image" curl --http3-only --ipv4 --noproxy '*' --cacert /tmp/diagnostic-ca.pem \
  --resolve "cloudflare-quic.com:443:$(cat "$out/address.txt")" --max-time 20 -sS -v -o /dev/null \
  -w 'http=%{http_version} status=%{http_code} ip=%{remote_ip} total=%{time_total}\n' \
  https://cloudflare-quic.com/ > "$out/reference-host-result.txt" 2> "$out/reference-host-verbose.txt"
printf 'exit=%s\n' "$?" >> "$out/reference-host-result.txt"
set -e
(cd "$out" && sha256sum probe libnetworkkmmcurl.so ca.pem reference-root.tar > input-sha256.txt)
