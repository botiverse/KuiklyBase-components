#!/usr/bin/env bash
# raft.46: reproduce a silently dead reused connection on loopback (needs sudo for iptables) and
# check the wrapper recovers: an idle dead connection is found before reuse (the next GET opens a
# new connection at once), and a request already on a dead connection fails over within the 10 s
# unacked-data deadline instead of the 30 s request timeout. Usage:
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
# Dead right away: the request itself is the unacked data, it never reached the server, and libcurl
# replays it on a new connection: the server sees the POST exactly once.
run POST 0 15000 "" 1
# Dead after the server took the POST and before its response: libcurl replays any request on a
# reused connection that died before a response byte arrived (Curl_retry_request), so the server
# sees this POST twice. Documented, not asserted: Chromium resends in the same case, and before
# raft.46 the same request hung for the full request timeout instead.
run POST 0 40000 slow
exit "$FAILED"
