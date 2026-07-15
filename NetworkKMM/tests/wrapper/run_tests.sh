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

echo "==> Starting test server on :$PORT"
python3 "$SCRIPT_DIR/test_server.py" "$PORT" &
SERVER_PID=$!
trap 'kill "$SERVER_PID" 2>/dev/null || true' EXIT
for _ in $(seq 1 50); do
  if curl -sf "http://127.0.0.1:$PORT/ok" >/dev/null 2>&1; then break; fi
  sleep 0.1
done

echo "==> Running behavior tests"
"$BUILD_DIR/wrapper_behavior_test" "http://127.0.0.1:$PORT"
