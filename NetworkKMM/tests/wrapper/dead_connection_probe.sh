#!/usr/bin/env bash
# raft.46: reproduce a silently dead reused connection on loopback (needs sudo for iptables) and
# check the wrapper recovers: an idle dead connection is found before reuse (the next GET opens a
# new connection at once), and a POST already on a dead connection fails within the 10 s
# unacked-data deadline instead of the 30 s request timeout, without ever being sent twice. Usage:
#   dead_connection_probe.sh [wrapper.cpp]   (defaults to the working-tree wrapper; pass an older
#                                             curl_wrapper.cpp to compare)
# run_tests.sh runs it when passwordless sudo iptables is available (GitHub-hosted runners).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CPP_ROOT="$(cd "${SCRIPT_DIR}/../../ohosApp/pbcurlwrapper/src/main/cpp" && pwd)"
WRAPPER="${1:-$CPP_ROOT/wrapper/src/curl_wrapper.cpp}"
BUILD_DIR="${SCRIPT_DIR}/build"
PORT="${PROBE_PORT:-18991}"
mkdir -p "$BUILD_DIR"
g++ -std=c++17 -O1 -g -DNETWORKKMM_WRAPPER_TESTING -I "$CPP_ROOT" -I "$CPP_ROOT/wrapper/include" \
  -I "$CPP_ROOT/wrapper/src" "$WRAPPER" "$CPP_ROOT/wrapper/src/log/curl_log.cpp" \
  "$CPP_ROOT/wrapper/src/utils/curl_utils.cpp" "$SCRIPT_DIR/dead_connection_probe.cpp" \
  -lcurl -lz -pthread -o "$BUILD_DIR/dead_connection_probe"
python3 "$SCRIPT_DIR/test_server.py" "$PORT" >/dev/null 2>&1 &
SERVER=$!
RULES=()
cleanup() {
  for rule in "${RULES[@]}"; do sudo iptables -D $rule 2>/dev/null || true; done
  kill "$SERVER" 2>/dev/null || true
}
trap cleanup EXIT
sleep 0.5
FAILED=0
post_count() { curl -s "http://127.0.0.1:$PORT/post-count"; }
run() {
  local method="$1" idle="$2" budget="$3" mode="${4:-}" expected_posts="${5:-}"
  coproc PROBE { "$BUILD_DIR/dead_connection_probe" "http://127.0.0.1:$PORT" "$method" "$idle" "$budget" "$mode"; }
  local line before
  before="$(post_count)"
  while read -r line <&"${PROBE[0]}"; do
    [[ "$line" == READY ]] && break
  done
  if [[ "$mode" == slow ]]; then
    # Wait until the server holds the POST, then cut the path before its response.
    for _ in $(seq 1 100); do [[ "$(post_count)" != "$before" ]] && break; sleep 0.05; done
  fi
  local cport
  cport="$(ss -tnH state established "( dport = :$PORT )" | awk '{print $3}' | sed 's/.*://' | head -1)"
  RULES=("OUTPUT -o lo -p tcp --sport $cport -j DROP" "OUTPUT -o lo -p tcp --dport $cport -j DROP")
  for rule in "${RULES[@]}"; do sudo iptables -I $rule; done
  echo >&"${PROBE[1]}"
  cat <&"${PROBE[0]}" || true
  local pid="${PROBE_PID:-}"
  if [[ -n "$pid" ]]; then wait "$pid" 2>/dev/null || FAILED=1; fi
  for rule in "${RULES[@]}"; do sudo iptables -D $rule 2>/dev/null || true; done
  RULES=()
  if [[ "$method" == POST ]]; then
    local received=$(( $(post_count) - before ))
    echo "server received the POST $received time(s)"
    if [[ -n "$expected_posts" && "$received" != "$expected_posts" ]]; then
      echo "FAIL: expected the server to receive the POST $expected_posts time(s)"
      FAILED=1
    fi
  fi
}
# Idle past keepalive (10 s) + unacked-data deadline (10 s): the kernel has failed the connection.
run GET "${IDLE_GET:-25}" 3000
# Dead right away (the POST never reached the server) and dead after the server took the POST
# (held 4 s, then the path is cut before its response): libcurl would replay both on a new
# connection (Curl_retry_request ignores the method), the second one as a duplicate. The wrapper
# refuses any replay that is not GET/HEAD: both fail within the unacked-data deadline, and the
# server sees the POST at most once.
run POST 0 15000 "" 0
run POST 0 40000 slow 1
exit "$FAILED"
