#!/usr/bin/env bash
#
# Self-tests for scripts/publish-lib.sh. Runs in PR CI without publishing
# anything: pure-logic unit tests for classify_http_code, plus integration tests
# for probe_url / classify_manifest against local mock HTTP servers exercising
# the fail-closed status classification, bounded retry/backoff, the same-host
# redirect policy (valid same-host redirect vs cross-host redirect), and a real
# refused connection.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=publish-lib.sh
source "$SCRIPT_DIR/publish-lib.sh"

# No real waiting in tests; small retry budget so transport tests fail fast.
export DATETIME_PROBE_BACKOFF_SECONDS=0
export DATETIME_PROBE_MAX_ATTEMPTS=3
export DATETIME_PROBE_CONNECT_TIMEOUT=2
export DATETIME_PROBE_MAX_TIME=5

pass=0
fail=0
check() {
  local label="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    echo "  OK   $label (= $actual)"
    pass=$(( pass + 1 ))
  else
    echo "  FAIL $label expected=$expected actual=$actual" >&2
    fail=$(( fail + 1 ))
  fi
}
# check_rc <label> <expected: zero|nonzero> <actual-rc>
check_rc() {
  local label="$1" expected="$2" rc="$3"
  if { [ "$expected" = "zero" ] && [ "$rc" -eq 0 ]; } || \
     { [ "$expected" = "nonzero" ] && [ "$rc" -ne 0 ]; }; then
    echo "  OK   $label (rc=$rc, expected $expected)"
    pass=$(( pass + 1 ))
  else
    echo "  FAIL $label rc=$rc expected $expected" >&2
    fail=$(( fail + 1 ))
  fi
}

echo "== classify_http_code unit tests =="
check "404 -> ABSENT"   "ABSENT" "$(classify_http_code 404)"
check "200 -> EXISTS"   "EXISTS" "$(classify_http_code 200)"
check "204 -> EXISTS"   "EXISTS" "$(classify_http_code 204)"
check "302 -> FAIL (bare redirect anomalous with -L)" "FAIL" "$(classify_http_code 302)"
check "301 -> FAIL"     "FAIL"   "$(classify_http_code 301)"
check "401 -> FAIL"     "FAIL"   "$(classify_http_code 401)"
check "403 -> FAIL"     "FAIL"   "$(classify_http_code 403)"
check "400 -> FAIL"     "FAIL"   "$(classify_http_code 400)"
check "429 -> RETRY"    "RETRY"  "$(classify_http_code 429)"
check "500 -> RETRY"    "RETRY"  "$(classify_http_code 500)"
check "503 -> RETRY"    "RETRY"  "$(classify_http_code 503)"
check "000 -> RETRY"    "RETRY"  "$(classify_http_code 000)"
check "empty -> RETRY"  "RETRY"  "$(classify_http_code "")"

echo "== mock HTTP server integration tests =="
PORT="${DATETIME_TEST_PORT:-18931}"
PORT2="${DATETIME_TEST_PORT2:-18933}"
export MOCK_REDIRECT_CROSS_TARGET="http://127.0.0.1:$PORT2/200"
python3 "$SCRIPT_DIR/mock-probe-server.py" "$PORT" &
server_pid=$!
python3 "$SCRIPT_DIR/mock-probe-server.py" "$PORT2" &
server2_pid=$!
trap 'kill "$server_pid" "$server2_pid" 2>/dev/null || true' EXIT
for p in "$PORT" "$PORT2"; do
  for _ in $(seq 1 50); do
    if curl -s -o /dev/null "http://127.0.0.1:$p/200" 2>/dev/null; then break; fi
    sleep 0.1
  done
done

BASE="http://127.0.0.1:$PORT"
U="testuser"; T="testtoken"

# Direct status classifications.
check "probe 404 -> ABSENT" "ABSENT" "$(probe_url "$BASE/404" "$U" "$T")"
check "probe 200 -> EXISTS" "EXISTS" "$(probe_url "$BASE/200" "$U" "$T")"

# Same-host redirect followed to final 200 -> EXISTS.
check "probe same-host redirect -> EXISTS" "EXISTS" "$(probe_url "$BASE/redirect200" "$U" "$T")"

# Cross-host redirect must fail closed (not EXISTS).
set +e
out="$(probe_url "$BASE/redirect-cross" "$U" "$T" 2>&1)"; rc=$?
set -e
check_rc "probe cross-host redirect fails closed" "nonzero" "$rc"
check "cross-host reason mentions redirect" "yes" "$(printf '%s' "$out" | grep -qi "redirect" && echo yes || echo no)"

# Fail-closed: 401 and 403 must hard-fail (non-zero exit), not ABSENT.
set +e; probe_url "$BASE/401" "$U" "$T" >/dev/null 2>&1; rc=$?; set -e
check_rc "probe 401 fails closed" "nonzero" "$rc"
set +e; probe_url "$BASE/403" "$U" "$T" >/dev/null 2>&1; rc=$?; set -e
check_rc "probe 403 fails closed" "nonzero" "$rc"

# Transient 429 then 200 -> EXISTS after retry (server flips after first hit).
check "probe 429-then-200 -> EXISTS" "EXISTS" "$(probe_url "$BASE/429once" "$U" "$T")"

# Persistent 500 -> hard fail after exhausting retries.
set +e; probe_url "$BASE/500" "$U" "$T" >/dev/null 2>&1; rc=$?; set -e
check_rc "probe persistent 500 fails closed" "nonzero" "$rc"

# Real refused connection (nothing listening on the discard port) -> the bounded
# retry path runs and then fails closed (regression for the 000000 bug, which
# classified FAIL immediately instead of retrying).
set +e
out="$(probe_url "http://127.0.0.1:9/not-listening" "$U" "$T" 2>&1)"; rc=$?
set -e
check_rc "probe refused connection fails closed" "nonzero" "$rc"
check "refused connection not 000000" "yes" "$(printf '%s' "$out" | grep -q "000000" && echo no || echo yes)"

# classify_manifest over multiple URLs.
check "manifest all-404 -> NONE"     "NONE"     "$(classify_manifest "$U" "$T" "$BASE/404" "$BASE/404b" "$BASE/404c")"
check "manifest all-200 -> COMPLETE" "COMPLETE" "$(classify_manifest "$U" "$T" "$BASE/200" "$BASE/200b" "$BASE/200c")"
check "manifest mixed -> PARTIAL"    "PARTIAL"  "$(classify_manifest "$U" "$T" "$BASE/200" "$BASE/404" "$BASE/200c")"

# Manifest with an auth failure must fail closed (not PARTIAL/NONE).
set +e; classify_manifest "$U" "$T" "$BASE/200" "$BASE/401" >/dev/null 2>&1; rc=$?; set -e
check_rc "manifest with 401 fails closed" "nonzero" "$rc"

echo "== result: pass=$pass fail=$fail =="
if [ "$fail" -ne 0 ]; then
  echo "PUBLISH_LIB_SELFTEST_FAIL" >&2
  exit 1
fi
echo "PUBLISH_LIB_SELFTEST_PASS"
