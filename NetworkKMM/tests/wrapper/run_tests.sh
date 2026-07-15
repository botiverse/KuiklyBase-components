#!/usr/bin/env bash
# Build pbcurlwrapper for the HOST (same sources, system libcurl/zlib) and run
# the behavior-contract tests against a local server. Locks down the wrapper's
# observable contract on every PR — status passthrough (the raft.3 bug class),
# error bodies, timeouts, redirects, POST bodies, content-encoding decode, and
# share-handle pooling.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CPP_ROOT="$(cd "${SCRIPT_DIR}/../../ohosApp/pbcurlwrapper/src/main/cpp" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/build"
PORT="${WRAPPER_TEST_PORT:-18923}"
PROXY_PORT="$((PORT + 1))"
HTTPS_PORT="$((PORT + 2))"
DELAYED_PROXY_PORT="$((PORT + 3))"
PROXY_MARKER="${BUILD_DIR}/stalled-connect-proxy.marker"
DELAYED_PROXY_MARKER="${BUILD_DIR}/delayed-connect-proxy.marker"
TLS_CERT="${BUILD_DIR}/phase-test-cert.pem"
TLS_KEY="${BUILD_DIR}/phase-test-key.pem"

mkdir -p "$BUILD_DIR"

echo "==> Building wrapper + tests for host"
g++ -std=c++17 -O1 -g \
  -I "$CPP_ROOT" \
  -I "$CPP_ROOT/wrapper/include" \
  "$CPP_ROOT/wrapper/src/curl_wrapper.cpp" \
  "$CPP_ROOT/wrapper/src/log/curl_log.cpp" \
  "$CPP_ROOT/wrapper/src/utils/curl_utils.cpp" \
  "$SCRIPT_DIR/wrapper_behavior_test.cpp" \
  -lcurl -lz \
  -o "$BUILD_DIR/wrapper_behavior_test"

WRAPPER_SYMBOLS="$(nm "$BUILD_DIR/wrapper_behavior_test")"
for symbol in StartRequestV27 StartStreamRequestV27 StartUploadRequestV27; do
  grep -Eq " [Tt] ${symbol}$" <<<"$WRAPPER_SYMBOLS"
done
for legacy_symbol in StartRequest StartStreamRequest StartUploadRequest; do
  if grep -Eq " [Tt] ${legacy_symbol}$" <<<"$WRAPPER_SYMBOLS"; then
    echo "legacy wrapper ABI symbol is still exported: ${legacy_symbol}" >&2
    exit 1
  fi
done

echo "==> Verifying bidirectional ABI skew fails at link time"
if g++ -std=c++17 \
  -I "$SCRIPT_DIR/abi_v26" \
  "$SCRIPT_DIR/abi_v26/caller.cpp" \
  "$CPP_ROOT/wrapper/src/curl_wrapper.cpp" \
  "$CPP_ROOT/wrapper/src/log/curl_log.cpp" \
  "$CPP_ROOT/wrapper/src/utils/curl_utils.cpp" \
  -I "$CPP_ROOT" \
  -I "$CPP_ROOT/wrapper/include" \
  -lcurl -lz \
  -o "$BUILD_DIR/v26-caller-v27-runtime" \
  2>"$BUILD_DIR/v26-caller-v27-runtime.log"; then
  echo "frozen v26 caller unexpectedly linked against v27 runtime" >&2
  exit 1
fi
grep -q "StartRequest" "$BUILD_DIR/v26-caller-v27-runtime.log"

if g++ -std=c++17 \
  -I "$CPP_ROOT/wrapper/include" \
  -I "$SCRIPT_DIR/abi_v26" \
  "$SCRIPT_DIR/abi_v27_caller.cpp" \
  "$SCRIPT_DIR/abi_v26/runtime.cpp" \
  -o "$BUILD_DIR/v27-caller-v26-runtime" \
  2>"$BUILD_DIR/v27-caller-v26-runtime.log"; then
  echo "v27 caller unexpectedly linked against frozen v26 runtime" >&2
  exit 1
fi
grep -q "StartRequestV27" "$BUILD_DIR/v27-caller-v26-runtime.log"

echo "==> Starting test server on :$PORT"
openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout "$TLS_KEY" \
  -out "$TLS_CERT" \
  -days 1 \
  -subj "/CN=127.0.0.1" \
  -addext "subjectAltName=IP:127.0.0.1" \
  >/dev/null 2>&1
python3 "$SCRIPT_DIR/test_server.py" "$PORT" &
SERVER_PID=$!
python3 "$SCRIPT_DIR/https_test_server.py" \
  --port "$HTTPS_PORT" \
  --cert "$TLS_CERT" \
  --key "$TLS_KEY" &
HTTPS_SERVER_PID=$!
python3 "$SCRIPT_DIR/connect_proxy.py" \
  --port "$PROXY_PORT" \
  --marker "$PROXY_MARKER" \
  --stall-connect &
PROXY_PID=$!
python3 "$SCRIPT_DIR/connect_proxy.py" \
  --port "$DELAYED_PROXY_PORT" \
  --marker "$DELAYED_PROXY_MARKER" \
  --connect-delay-ms 400 &
DELAYED_PROXY_PID=$!
trap 'kill "$SERVER_PID" "$HTTPS_SERVER_PID" "$PROXY_PID" "$DELAYED_PROXY_PID" 2>/dev/null || true' EXIT
for _ in $(seq 1 50); do
  if curl -sf "http://127.0.0.1:$PORT/ok" >/dev/null 2>&1; then break; fi
  sleep 0.1
done

echo "==> Running behavior tests"
"$BUILD_DIR/wrapper_behavior_test" \
  "http://127.0.0.1:$PORT" \
  "http://127.0.0.1:$PROXY_PORT" \
  "http://127.0.0.1:$DELAYED_PROXY_PORT" \
  "https://127.0.0.1:$HTTPS_PORT" \
  "$TLS_CERT"
