#!/usr/bin/env bash
#
# Self-tests for scripts/publish-lib.sh. Runs in PR CI without publishing
# anything: pure-logic unit tests for classify_http_code, plus integration tests
# for probe_url / classify_manifest against a local mock HTTP server that
# returns configurable status codes (including a transient 429-then-200 case and
# a persistent 500 case to exercise the bounded retry / fail-closed behavior).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=publish-lib.sh
source "$SCRIPT_DIR/publish-lib.sh"

# No real waiting in tests.
export DATETIME_PROBE_BACKOFF_SECONDS=0
export DATETIME_PROBE_MAX_ATTEMPTS=3

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

echo "== classify_http_code unit tests =="
check "404 -> ABSENT"   "ABSENT" "$(classify_http_code 404)"
check "200 -> EXISTS"   "EXISTS" "$(classify_http_code 200)"
check "204 -> EXISTS"   "EXISTS" "$(classify_http_code 204)"
check "302 -> EXISTS"   "EXISTS" "$(classify_http_code 302)"
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
python3 "$SCRIPT_DIR/mock-probe-server.py" "$PORT" &
server_pid=$!
trap 'kill "$server_pid" 2>/dev/null || true' EXIT
# Wait for the server to accept connections.
for _ in $(seq 1 50); do
  if curl -s -o /dev/null "http://127.0.0.1:$PORT/200" 2>/dev/null; then break; fi
  sleep 0.1
done

BASE="http://127.0.0.1:$PORT"
U="testuser"; T="testtoken"

# probe_url single-shot classifications.
check "probe 404 -> ABSENT" "ABSENT" "$(probe_url "$BASE/404" "$U" "$T")"
check "probe 200 -> EXISTS" "EXISTS" "$(probe_url "$BASE/200" "$U" "$T")"

# Fail-closed: 401 and 403 must hard-fail (non-zero exit), not ABSENT.
if probe_url "$BASE/401" "$U" "$T" >/dev/null 2>&1; then
  check "probe 401 fails closed" "nonzero" "zero"; else check "probe 401 fails closed" "nonzero" "nonzero"; fi
if probe_url "$BASE/403" "$U" "$T" >/dev/null 2>&1; then
  check "probe 403 fails closed" "nonzero" "zero"; else check "probe 403 fails closed" "nonzero" "nonzero"; fi

# Transient 429 then 200 -> EXISTS after retry (server flips after first hit).
check "probe 429-then-200 -> EXISTS" "EXISTS" "$(probe_url "$BASE/429once" "$U" "$T")"

# Persistent 500 -> hard fail after exhausting retries.
if probe_url "$BASE/500" "$U" "$T" >/dev/null 2>&1; then
  check "probe persistent 500 fails closed" "nonzero" "zero"; else check "probe persistent 500 fails closed" "nonzero" "nonzero"; fi

# classify_manifest over multiple URLs.
check "manifest all-404 -> NONE"     "NONE"     "$(classify_manifest "$U" "$T" "$BASE/404" "$BASE/404b" "$BASE/404c")"
check "manifest all-200 -> COMPLETE" "COMPLETE" "$(classify_manifest "$U" "$T" "$BASE/200" "$BASE/200b" "$BASE/200c")"
check "manifest mixed -> PARTIAL"    "PARTIAL"  "$(classify_manifest "$U" "$T" "$BASE/200" "$BASE/404" "$BASE/200c")"

# Manifest with an auth failure must fail closed (not PARTIAL/NONE).
if classify_manifest "$U" "$T" "$BASE/200" "$BASE/401" >/dev/null 2>&1; then
  check "manifest with 401 fails closed" "nonzero" "zero"; else check "manifest with 401 fails closed" "nonzero" "nonzero"; fi

echo "== result: pass=$pass fail=$fail =="
if [ "$fail" -ne 0 ]; then
  echo "PUBLISH_LIB_SELFTEST_FAIL" >&2
  exit 1
fi
echo "PUBLISH_LIB_SELFTEST_PASS"
