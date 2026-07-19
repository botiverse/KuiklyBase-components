#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NETWORK_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
RUNTIME_ROOT="${NETWORK_ROOT}/build/ios-curl-runtime"
CERT_ROOT="${RUNTIME_ROOT}/certificates"
CA_PATH="${CERT_ROOT}/ca.pem"
WRONG_CA_PATH="${CERT_ROOT}/wrong-ca.pem"
PUBLIC_CA_PATH="${CERT_ROOT}/public-ca.pem"
PORT="${NETWORKKMM_IOS_CURL_RUNTIME_PORT:-18924}"
UNKNOWN_PORT=$((PORT + 1))
EXPIRED_PORT=$((PORT + 2))
MISMATCH_PORT=$((PORT + 3))
PROXY_PORT=$((PORT + 4))
PROXY_MARKER="${RUNTIME_ROOT}/proxy-connect.log"

case "$(uname -m)" in
  arm64)
    IOS_CURL_TEST_TASK="iosSimulatorArm64Test"
    ;;
  x86_64)
    IOS_CURL_TEST_TASK="iosX64Test"
    ;;
  *)
    echo "Unsupported macOS runner architecture: $(uname -m)" >&2
    exit 2
    ;;
esac

boot_ios_simulator() {
  local device_id
  device_id="$(xcrun simctl list devices available -j | python3 -c '
import json, sys
data = json.load(sys.stdin)
for runtime, devices in data["devices"].items():
    if "iOS" not in runtime:
        continue
    for device in devices:
        if device.get("isAvailable") and device["name"].startswith("iPhone"):
            print(device["udid"])
            raise SystemExit(0)
raise SystemExit("No available iPhone simulator")
')"
  echo "booting iOS runtime-test simulator ${device_id}"
  xcrun simctl boot "$device_id" 2>/dev/null || true
  xcrun simctl bootstatus "$device_id" -b
}

boot_ios_simulator
mkdir -p "$RUNTIME_ROOT"
"${SCRIPT_DIR}/generate-curl-test-certificates.sh" "$CERT_ROOT"
"${SCRIPT_DIR}/prepare-app-owned-ca-bundle.sh" "$PUBLIC_CA_PATH"
rm -f "$PROXY_MARKER"

python3 "${NETWORK_ROOT}/tests/wrapper/https_test_server.py" \
  --port "$PORT" \
  --cert "${CERT_ROOT}/valid.pem" \
  --key "${CERT_ROOT}/valid-key.pem" &
VALID_PID=$!
python3 "${NETWORK_ROOT}/tests/wrapper/https_test_server.py" \
  --port "$UNKNOWN_PORT" \
  --cert "${CERT_ROOT}/unknown.pem" \
  --key "${CERT_ROOT}/unknown-key.pem" &
UNKNOWN_PID=$!
python3 "${NETWORK_ROOT}/tests/wrapper/https_test_server.py" \
  --port "$EXPIRED_PORT" \
  --cert "${CERT_ROOT}/expired.pem" \
  --key "${CERT_ROOT}/expired-key.pem" &
EXPIRED_PID=$!
python3 "${NETWORK_ROOT}/tests/wrapper/https_test_server.py" \
  --port "$MISMATCH_PORT" \
  --cert "${CERT_ROOT}/mismatch.pem" \
  --key "${CERT_ROOT}/mismatch-key.pem" &
MISMATCH_PID=$!
python3 "${NETWORK_ROOT}/tests/wrapper/connect_proxy.py" \
  --port "$PROXY_PORT" \
  --marker "$PROXY_MARKER" &
PROXY_PID=$!
trap 'kill "$VALID_PID" "$UNKNOWN_PID" "$EXPIRED_PID" "$MISMATCH_PID" "$PROXY_PID" 2>/dev/null || true' EXIT

for port in "$PORT" "$UNKNOWN_PORT" "$EXPIRED_PORT" "$MISMATCH_PORT"; do
  for _ in $(seq 1 50); do
    if curl -kfsS "https://127.0.0.1:${port}/ok" >/dev/null 2>&1; then
      break
    fi
    sleep 0.1
  done
  curl -kfsS "https://127.0.0.1:${port}/ok" >/dev/null
done

sha256_file() {
  shasum -a 256 "$1" | awk '{print $1}'
}

resolve_url_ipv4() {
  python3 - "$1" <<'PY'
import socket
import sys
from urllib.parse import urlsplit

url = urlsplit(sys.argv[1])
host = url.hostname
if not host:
    raise SystemExit(f"URL has no hostname: {sys.argv[1]}")
port = url.port or (443 if url.scheme == "https" else 80)
addresses = []
for result in socket.getaddrinfo(host, port, socket.AF_INET, socket.SOCK_STREAM):
    address = result[4][0]
    if address not in addresses:
        addresses.append(address)
if not addresses:
    raise SystemExit(f"No IPv4 address resolved for {host}")
print(f"{host}:{port}:{','.join(addresses)}")
PY
}

export NETWORKKMM_IOS_CURL_RUNTIME_CA_PATH="$CA_PATH"
export NETWORKKMM_IOS_CURL_RUNTIME_CA_SHA256="$(sha256_file "$CA_PATH")"
export NETWORKKMM_IOS_CURL_RUNTIME_WRONG_CA_PATH="$WRONG_CA_PATH"
export NETWORKKMM_IOS_CURL_RUNTIME_WRONG_CA_SHA256="$(sha256_file "$WRONG_CA_PATH")"
export NETWORKKMM_IOS_CURL_RUNTIME_PUBLIC_CA_PATH="$PUBLIC_CA_PATH"
export NETWORKKMM_IOS_CURL_RUNTIME_PUBLIC_CA_SHA256="$(sha256_file "$PUBLIC_CA_PATH")"
export NETWORKKMM_IOS_CURL_RUNTIME_HTTP3_URL="${NETWORKKMM_IOS_CURL_RUNTIME_HTTP3_URL:-https://cloudflare-quic.com/}"
export NETWORKKMM_IOS_CURL_RUNTIME_H2_FALLBACK_URL="${NETWORKKMM_IOS_CURL_RUNTIME_H2_FALLBACK_URL:-https://github.com/robots.txt}"
export NETWORKKMM_IOS_CURL_RUNTIME_HTTP3_RESOLVE="${NETWORKKMM_IOS_CURL_RUNTIME_HTTP3_RESOLVE:-$(resolve_url_ipv4 "$NETWORKKMM_IOS_CURL_RUNTIME_HTTP3_URL")}"
export NETWORKKMM_IOS_CURL_RUNTIME_H2_FALLBACK_RESOLVE="${NETWORKKMM_IOS_CURL_RUNTIME_H2_FALLBACK_RESOLVE:-$(resolve_url_ipv4 "$NETWORKKMM_IOS_CURL_RUNTIME_H2_FALLBACK_URL")}"
echo "HTTP/3 resolve entry: ${NETWORKKMM_IOS_CURL_RUNTIME_HTTP3_RESOLVE}"
echo "HTTP/2 fallback resolve entry: ${NETWORKKMM_IOS_CURL_RUNTIME_H2_FALLBACK_RESOLVE}"
export NETWORKKMM_IOS_CURL_RUNTIME_TOTAL_FAILURE_URL="${NETWORKKMM_IOS_CURL_RUNTIME_TOTAL_FAILURE_URL:-https://127.0.0.1:1/}"
export NETWORKKMM_IOS_CURL_RUNTIME_URL="${NETWORKKMM_IOS_CURL_RUNTIME_URL:-https://127.0.0.1:${PORT}/stream}"
export NETWORKKMM_IOS_CURL_RUNTIME_UPLOAD_URL="${NETWORKKMM_IOS_CURL_RUNTIME_UPLOAD_URL:-https://127.0.0.1:${PORT}/upload}"
export NETWORKKMM_IOS_CURL_RUNTIME_CANCEL_URL="${NETWORKKMM_IOS_CURL_RUNTIME_CANCEL_URL:-https://127.0.0.1:${PORT}/slow}"
export NETWORKKMM_IOS_CURL_RUNTIME_UNKNOWN_CA_URL="https://127.0.0.1:${UNKNOWN_PORT}/ok"
export NETWORKKMM_IOS_CURL_RUNTIME_EXPIRED_URL="https://127.0.0.1:${EXPIRED_PORT}/ok"
export NETWORKKMM_IOS_CURL_RUNTIME_MISMATCH_URL="https://127.0.0.1:${MISMATCH_PORT}/ok"
export NETWORKKMM_IOS_CURL_RUNTIME_PROXY_URL="http://127.0.0.1:${PROXY_PORT}"
: "${NETWORKKMM_IOS_CURL_OPTIONAL_API_EXPECTATION:?set to unavailable for the old artifact or available for a fresh artifact}"
export NETWORKKMM_IOS_CURL_OPTIONAL_API_EXPECTATION
# simctl only forwards variables carrying the SIMCTL_CHILD_ prefix.
export SIMCTL_CHILD_NETWORKKMM_IOS_CURL_RUNTIME_CA_PATH="$NETWORKKMM_IOS_CURL_RUNTIME_CA_PATH"
export SIMCTL_CHILD_NETWORKKMM_IOS_CURL_RUNTIME_CA_SHA256="$NETWORKKMM_IOS_CURL_RUNTIME_CA_SHA256"
export SIMCTL_CHILD_NETWORKKMM_IOS_CURL_RUNTIME_WRONG_CA_PATH="$NETWORKKMM_IOS_CURL_RUNTIME_WRONG_CA_PATH"
export SIMCTL_CHILD_NETWORKKMM_IOS_CURL_RUNTIME_WRONG_CA_SHA256="$NETWORKKMM_IOS_CURL_RUNTIME_WRONG_CA_SHA256"
export SIMCTL_CHILD_NETWORKKMM_IOS_CURL_RUNTIME_PUBLIC_CA_PATH="$NETWORKKMM_IOS_CURL_RUNTIME_PUBLIC_CA_PATH"
export SIMCTL_CHILD_NETWORKKMM_IOS_CURL_RUNTIME_PUBLIC_CA_SHA256="$NETWORKKMM_IOS_CURL_RUNTIME_PUBLIC_CA_SHA256"
export SIMCTL_CHILD_NETWORKKMM_IOS_CURL_RUNTIME_HTTP3_URL="$NETWORKKMM_IOS_CURL_RUNTIME_HTTP3_URL"
export SIMCTL_CHILD_NETWORKKMM_IOS_CURL_RUNTIME_H2_FALLBACK_URL="$NETWORKKMM_IOS_CURL_RUNTIME_H2_FALLBACK_URL"
export SIMCTL_CHILD_NETWORKKMM_IOS_CURL_RUNTIME_HTTP3_RESOLVE="$NETWORKKMM_IOS_CURL_RUNTIME_HTTP3_RESOLVE"
export SIMCTL_CHILD_NETWORKKMM_IOS_CURL_RUNTIME_H2_FALLBACK_RESOLVE="$NETWORKKMM_IOS_CURL_RUNTIME_H2_FALLBACK_RESOLVE"
export SIMCTL_CHILD_NETWORKKMM_IOS_CURL_RUNTIME_TOTAL_FAILURE_URL="$NETWORKKMM_IOS_CURL_RUNTIME_TOTAL_FAILURE_URL"
export SIMCTL_CHILD_NETWORKKMM_IOS_CURL_RUNTIME_URL="$NETWORKKMM_IOS_CURL_RUNTIME_URL"
export SIMCTL_CHILD_NETWORKKMM_IOS_CURL_RUNTIME_UPLOAD_URL="$NETWORKKMM_IOS_CURL_RUNTIME_UPLOAD_URL"
export SIMCTL_CHILD_NETWORKKMM_IOS_CURL_RUNTIME_CANCEL_URL="$NETWORKKMM_IOS_CURL_RUNTIME_CANCEL_URL"
export SIMCTL_CHILD_NETWORKKMM_IOS_CURL_RUNTIME_UNKNOWN_CA_URL="$NETWORKKMM_IOS_CURL_RUNTIME_UNKNOWN_CA_URL"
export SIMCTL_CHILD_NETWORKKMM_IOS_CURL_RUNTIME_EXPIRED_URL="$NETWORKKMM_IOS_CURL_RUNTIME_EXPIRED_URL"
export SIMCTL_CHILD_NETWORKKMM_IOS_CURL_RUNTIME_MISMATCH_URL="$NETWORKKMM_IOS_CURL_RUNTIME_MISMATCH_URL"
export SIMCTL_CHILD_NETWORKKMM_IOS_CURL_RUNTIME_PROXY_URL="$NETWORKKMM_IOS_CURL_RUNTIME_PROXY_URL"
export SIMCTL_CHILD_NETWORKKMM_IOS_CURL_OPTIONAL_API_EXPECTATION="$NETWORKKMM_IOS_CURL_OPTIONAL_API_EXPECTATION"

cd "$NETWORK_ROOT"
echo "running ${IOS_CURL_TEST_TASK} on $(uname -m)"
./gradlew \
  ":network:${IOS_CURL_TEST_TASK}" \
  --tests 'com.tencent.kmm.network.internal.platform.IosCurlRuntimeTest' \
  --rerun-tasks \
  --no-daemon \
  --console=plain \
  --stacktrace

test -s "$PROXY_MARKER" || {
  echo "manual proxy gate did not observe an HTTP CONNECT" >&2
  exit 2
}
