#!/usr/bin/env bash
#
# End-to-end self-tests for the terminal verifier scripts/verify-published.sh
# (and verify-coordinates.py + version-explicit legal gate). Runs in PR CI
# without publishing or any iOS/OHOS toolchain: a synthetic but structurally-
# valid publication matrix is generated and served over a local HTTP server,
# then the verifier is exercised on the happy path and against injected faults
# (corrupt OHOS-root legal bytes, wrong coordinate, refused transport). This is
# the gate that proves the terminal verifier itself is not false-green.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

VERSION="0.1.0-raft.0"
LEGAL_DIR="$PROJECT_DIR/legal/META-INF"
BASE_PORT="${DATETIME_TEST_PORT:-18941}"

pass=0
fail=0
note_ok()   { echo "  OK   $1"; pass=$(( pass + 1 )); }
note_fail() { echo "  FAIL $1" >&2; fail=$(( fail + 1 )); }

# Each case uses its own port to avoid any reuse race between cases.
serve() {  # serve <dir> <port>
  ( cd "$1" && exec python3 -m http.server "$2" >/dev/null 2>&1 & echo $! > "/tmp/vp-server-$2.pid" )
  for _ in $(seq 1 50); do
    curl -s -o /dev/null "http://127.0.0.1:$2/" 2>/dev/null && break
    sleep 0.1
  done
}
stop_serve() {  # stop_serve <port>
  local pidfile="/tmp/vp-server-$1.pid"
  [ -f "$pidfile" ] && kill "$(cat "$pidfile")" 2>/dev/null || true
  rm -f "$pidfile"
  sleep 0.2
}

run_verifier() {  # run_verifier <repo-base>
  DATETIME_REPO_BASE="$1" \
  MAVEN_VERSION="$VERSION" \
  GITHUB_PACKAGES_USERNAME=u GITHUB_PACKAGES_TOKEN=t \
  DATETIME_READBACK_BACKOFF_SECONDS=0 DATETIME_READBACK_MAX_ATTEMPTS=2 \
  DATETIME_PROBE_CONNECT_TIMEOUT=2 DATETIME_PROBE_MAX_TIME=10 \
  bash "$SCRIPT_DIR/verify-published.sh" 2>&1
}

echo "== happy path: full synthetic matrix -> READBACK_PASS =="
m2="$(mktemp -d)"; P=$BASE_PORT
python3 "$SCRIPT_DIR/gen-test-publication.py" "$m2" "$VERSION" "$LEGAL_DIR" >/dev/null
serve "$m2" "$P"
set +e; out="$(run_verifier "http://127.0.0.1:$P/build/raft/kuiklybase")"; rc=$?; set -e
stop_serve "$P"
if [ "$rc" -eq 0 ] && printf '%s' "$out" | grep -q "READBACK_PASS"; then
  note_ok "happy path READBACK_PASS"
else
  note_fail "happy path expected READBACK_PASS (rc=$rc)"; printf '%s\n' "$out" | tail -8 >&2
fi
rm -rf "$m2"

echo "== fault: corrupt OHOS-root legal bytes -> must FAIL (not false-green) =="
m2b="$(mktemp -d)"; P=$(( BASE_PORT + 1 ))
python3 "$SCRIPT_DIR/gen-test-publication.py" "$m2b" "$VERSION" "$LEGAL_DIR" >/dev/null
# Corrupt ONLY the OHOS root jar's PROVENANCE (normal root stays valid): the exact
# false-green the version-explicit legal gate must now catch.
ohos_root_jar="$m2b/build/raft/kuiklybase/datetime/$VERSION-ohos/datetime-$VERSION-ohos.jar"
tmp_x="$(mktemp -d)"
( cd "$tmp_x" && unzip -o -q "$ohos_root_jar" && echo "CORRUPTED" > META-INF/PROVENANCE.md && rm -f "$ohos_root_jar" && zip -q -r "$ohos_root_jar" META-INF )
rm -rf "$tmp_x"
serve "$m2b" "$P"
set +e; out="$(run_verifier "http://127.0.0.1:$P/build/raft/kuiklybase")"; rc=$?; set -e
stop_serve "$P"
if [ "$rc" -ne 0 ] && printf '%s' "$out" | grep -q "FAIL ohos-metadata-jar"; then
  note_ok "corrupt OHOS-root legal fails closed"
else
  note_fail "corrupt OHOS-root legal FALSE-GREEN (rc=$rc)"; printf '%s\n' "$out" | tail -6 >&2
fi
rm -rf "$m2b"

echo "== fault: wrong coordinate in android module -> COORDINATE FAIL =="
m2c="$(mktemp -d)"; P=$(( BASE_PORT + 2 ))
python3 "$SCRIPT_DIR/gen-test-publication.py" "$m2c" "$VERSION" "$LEGAL_DIR" >/dev/null
android_module="$m2c/build/raft/kuiklybase/datetime-android/$VERSION/datetime-android-$VERSION.module"
sed -i 's#"module": "datetime-android"#"module": "datetime-WRONG"#' "$android_module"
serve "$m2c" "$P"
set +e; out="$(run_verifier "http://127.0.0.1:$P/build/raft/kuiklybase")"; rc=$?; set -e
stop_serve "$P"
if [ "$rc" -ne 0 ] && printf '%s' "$out" | grep -qi "COORDINATE FAIL"; then
  note_ok "wrong coordinate fails closed"
else
  note_fail "wrong coordinate not caught (rc=$rc)"; printf '%s\n' "$out" | tail -6 >&2
fi
rm -rf "$m2c"

echo "== fault: refused transport -> fails closed via retry =="
set +e; out="$(run_verifier "http://127.0.0.1:9/build/raft/kuiklybase")"; rc=$?; set -e
if [ "$rc" -ne 0 ] && printf '%s' "$out" | grep -qi "READBACK FAIL"; then
  note_ok "refused transport fails closed"
else
  note_fail "refused transport not fail-closed (rc=$rc)"
fi

echo "== result: pass=$pass fail=$fail =="
if [ "$fail" -ne 0 ]; then echo "VERIFY_PUBLISHED_SELFTEST_FAIL" >&2; exit 1; fi
echo "VERIFY_PUBLISHED_SELFTEST_PASS"
