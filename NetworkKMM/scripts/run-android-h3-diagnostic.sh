#!/usr/bin/env bash
# Runs inside emulator-runner. Preserves the original instrumentation exit code.
# Guest reference runs as root in Debian chroot, NOT as the Android app UID.
# Its purpose is to test the same emulator's network with independent QUIC code.
set -euo pipefail
out="${1:?evidence directory}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
guest=/data/local/tmp/networkkmm-h3-diagnostic
ip="$(cat "$out/address.txt")"
[[ "$ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]
date -u +%FT%TZ > "$out/guest-start.txt"
adb root > "$out/adb-root.txt"
adb wait-for-device
adb shell 'id; uname -a; getprop ro.build.fingerprint; getprop ro.product.cpu.abi; getenforce; ip addr; ip route' > "$out/guest-identity.txt"
adb shell "mkdir -p $guest/reference"
adb push "$out/probe" "$out/libnetworkkmmcurl.so" "$out/ca.pem" "$out/reference-root.tar" "$guest/" > "$out/adb-push.txt"
adb shell "chmod 755 $guest/probe; cd $guest; sha256sum probe libnetworkkmmcurl.so ca.pem reference-root.tar" > "$out/guest-input-sha256.txt"
adb shell "cd $guest/reference; tar xf ../reference-root.tar"
# CI disposable emulator only; record SELinux setting before changing it for
# execution of the independent glibc reference. Both guest probes use this state.
adb shell "setenforce 0; mount -o bind /dev $guest/reference/dev; printf 'nameserver 10.0.2.3\n' > $guest/reference/etc/resolv.conf"
adb shell "getenforce; chroot $guest/reference /usr/bin/curl --version" > "$out/reference-guest-version.txt"
reference_image="$(tail -n 1 "$out/reference-image.txt")"
probe() {
  local label="$1"
  mkdir -p "$out/$label"
  set +e
  date -u +%FT%TZ > "$out/$label/host-start.txt"
  docker run --rm "$reference_image" curl --http3-only --ipv4 --noproxy '*' --cacert /tmp/diagnostic-ca.pem \
    --resolve "cloudflare-quic.com:443:$ip" --max-time 20 -sS -v -o /dev/null \
    -w 'http=%{http_version} status=%{http_code} ip=%{remote_ip} total=%{time_total}\n' \
    https://cloudflare-quic.com/ > "$out/$label/reference-host.txt" 2>&1
  echo "host_exit=$?" >> "$out/$label/reference-host.txt"
  for mode in h3-only h3-fallback h2; do
    case "$mode" in h3-only) flag=--http3-only;; h3-fallback) flag=--http3;; h2) flag=--http2;; esac
    date -u +%FT%TZ > "$out/$label/$mode-start.txt"
    adb shell "$guest/probe $guest/libnetworkkmmcurl.so $guest/ca.pem $mode $ip" > "$out/$label/committed-$mode.txt" 2>&1
    echo "adb_exit=$?" >> "$out/$label/committed-$mode.txt"
    adb shell "chroot $guest/reference /usr/bin/curl $flag --ipv4 --noproxy '*' --cacert /tmp/diagnostic-ca.pem --resolve cloudflare-quic.com:443:$ip --max-time 20 -sS -v -o /dev/null -w 'http=%{http_version} status=%{http_code} ip=%{remote_ip} total=%{time_total}\n' https://cloudflare-quic.com/" > "$out/$label/reference-$mode.txt" 2>&1
    echo "adb_exit=$?" >> "$out/$label/reference-$mode.txt"
    date -u +%FT%TZ > "$out/$label/$mode-end.txt"
  done
  set -e
}
# sys.boot_completed is not a network-ready signal. Wait for a real route,
# without configuring/changing the emulator's network or treating H3 as readiness.
route_ready=false
for attempt in $(seq 1 60); do
  if adb shell "ip route get $ip" > "$out/route-ready.txt" 2>&1; then
    route_ready=true
    break
  fi
  sleep 2
done
adb shell 'ip addr; ip rule; ip route show table all; dumpsys connectivity' > "$out/guest-network-ready.txt"
if [ "$route_ready" != true ]; then
  echo 'No route after 120 seconds; probes are setup failure, not H3 evidence.' >&2
  exit 2
fi
probe before
# Install and hash the same prebuilt instrumentation APK before Gradle executes it.
mapfile -t apks < <(find "$root/network/build/outputs/apk/androidTest" -name '*.apk')
test "${#apks[@]}" = 1
adb install -t -r --bypass-low-target-sdk-block "${apks[0]}" > "$out/explicit-install.txt"
adb shell 'pm path com.tencent.tmm.networkkmm.test' > "$out/installed-apk-path.txt"
installed_apk="$(tr -d '\r' < "$out/installed-apk-path.txt" | sed -n 's/^package://p' | head -n 1)"
test -n "$installed_apk"
adb shell "sha256sum '$installed_apk'" > "$out/installed-apk-sha256.txt"
local_apk_sha="$(sha256sum "${apks[0]}" | cut -d ' ' -f 1)"
guest_apk_sha="$(cut -d ' ' -f 1 "$out/installed-apk-sha256.txt")"
test "$local_apk_sha" = "$guest_apk_sha"
adb logcat -c
adb logcat -v threadtime > "$out/instrumentation-logcat.txt" &
log_pid=$!
trap 'kill "$log_pid" 2>/dev/null || true' EXIT
set +e
(cd "$root" && ./gradlew :network:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.tencent.kmm.network.internal.platform.AndroidCurlRuntimeInstrumentedTest \
  --no-daemon --stacktrace) 2>&1 | tee "$out/original-gate.txt"
gate_rc=${PIPESTATUS[0]}
set -e
printf '%s\n' "$gate_rc" > "$out/original-gate-exit.txt"
kill "$log_pid" 2>/dev/null || true
wait "$log_pid" 2>/dev/null || true
trap - EXIT
# Pin test APKs and their embedded .so, not only the source tree's library.
python3 - "$root" "$out" <<'PY'
import hashlib, json, pathlib, sys, zipfile
root, out = map(pathlib.Path, sys.argv[1:])
rows=[]
committed_sha=hashlib.sha256((root/'network/libs/android/x86_64/libnetworkkmmcurl.so').read_bytes()).hexdigest()
for apk in (root/'network/build/outputs/apk').rglob('*.apk'):
    item={'path':str(apk.relative_to(root)), 'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(), 'libs':[]}
    with zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if name.endswith('libnetworkkmmcurl.so'):
                sha=hashlib.sha256(z.read(name)).hexdigest()
                item['libs'].append({'entry':name, 'sha256':sha})
                if name == 'lib/x86_64/libnetworkkmmcurl.so':
                    assert sha == committed_sha, 'test APK differs from committed x86_64 library'
    rows.append(item)
(out/'apk-identities.json').write_text(json.dumps(rows,indent=2)+'\n')
PY
adb shell pm list packages > "$out/installed-packages.txt"
adb shell dumpsys package com.tencent.tmm.networkkmm.test > "$out/installed-test-package.txt"
# connectedDebugAndroidTest may uninstall on completion; logs/APK pin that fact.
probe after
date -u +%FT%TZ > "$out/guest-end.txt"
exit "$gate_rc"
