#!/usr/bin/env bash
# Diagnostic only: probe HTTP/3 reachability of the public h3 gate endpoint from the hosted
# runner, so an Android `http3` gate result (HTTP_2 instead of HTTP_3) can be narrowed down.
#
# This is a runner-side probe. The container curl, the emulator and the app's curl differ in
# implementation and network path, so a probe failure alone does not establish the root
# cause of an Android gate failure. The probe never fails the job: every outcome, including
# its own errors, is written to the evidence directory and the script exits 0.
#
# Usage: probe-runner-http3.sh <evidence-dir> <label>
set -uo pipefail

evidence_dir="${1:?evidence dir}"
label="${2:?label}"
url="https://cloudflare-quic.com/"
# Only the base image is pinned (digest). curl itself is installed with apt at run time, so the
# package version follows the Debian archive and is recorded per run (curl_package) rather than
# fixed; trixie's curl package is built with nghttp3 (HTTP3 feature).
image="debian:trixie-slim@sha256:a99cfc517144bc59b1978475ec53b46ecabec7e43635402ee5b77cc54cd1b20a"
per_request_seconds=20

out="$evidence_dir/$label"
mkdir -p "$out"
summary="$out/summary.txt"

{
  echo "label=$label"
  echo "utc_start=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "url=$url"
  echo "image=$image"
} > "$summary"

# One container run: install curl, record version/features, then three requests.
# Each request: verbose log to its own file, -w line to the summary, exit code recorded.
# The container id is captured so a run cut off by `timeout` is still force-removed below.
cidfile="$(mktemp -u "${TMPDIR:-/tmp}/h3-probe-cid.XXXXXX")"
timeout 240 docker run --rm --cidfile "$cidfile" -v "$out:/out" "$image" bash -c '
  set -u
  url="$1"; t="$2"
  if ! { apt-get update -qq && apt-get install -y -qq --no-install-recommends curl ca-certificates; } > /out/apt.log 2>&1; then
    echo "setup=apt_failed" >> /out/summary.txt
    exit 0
  fi
  dpkg-query -W -f="curl_package=\${Version}\n" curl >> /out/summary.txt
  curl --version > /out/curl-version.txt 2>&1
  if grep -q "^Features:.* HTTP3" /out/curl-version.txt; then
    echo "curl_http3_feature=yes" >> /out/summary.txt
  else
    echo "curl_http3_feature=no" >> /out/summary.txt
  fi
  probe() {
    local name="$1"; shift
    curl "$@" -sS -o /dev/null -v --max-time "$t" \
      -w "${name}: http_version=%{http_version} code=%{http_code} connect=%{time_connect} appconnect=%{time_appconnect} total=%{time_total}\n" \
      "$url" >> /out/summary.txt 2> "/out/${name}.verbose.txt"
    echo "${name}: exit=$?" >> /out/summary.txt
  }
  probe h3_only --http3-only
  probe h3_with_fallback --http3
  probe h2_baseline --http2
' bash "$url" "$per_request_seconds" >> "$out/docker.log" 2>&1
echo "docker_exit=$?" >> "$summary"
if [ -s "$cidfile" ]; then
  docker rm -f "$(cat "$cidfile")" > /dev/null 2>&1 || true
fi
rm -f "$cidfile"
echo "utc_end=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$summary"
cat "$summary"
exit 0
