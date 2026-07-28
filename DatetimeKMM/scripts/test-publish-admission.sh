#!/usr/bin/env bash
#
# Admission self-tests for scripts/publish-github-packages.sh. Runs in PR CI
# without publishing: points the script's Maven base at a local mock server and
# exercises the fail-closed NONE / COMPLETE / error admission paths in dry-run.
# (PARTIAL classification is covered by test-publish-lib.sh classify_manifest.)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT="${DATETIME_TEST_PORT:-18932}"

pass=0
fail=0
check_contains() {
  local label="$1" needle="$2" haystack="$3"
  if printf '%s' "$haystack" | grep -qF "$needle"; then
    echo "  OK   $label"
    pass=$(( pass + 1 ))
  else
    echo "  FAIL $label (missing: $needle)" >&2
    printf '%s\n' "$haystack" | sed 's/^/    | /' >&2
    fail=$(( fail + 1 ))
  fi
}

start_mock() {
  local default_code="$1"
  MOCK_DEFAULT_CODE="$default_code" python3 "$SCRIPT_DIR/mock-probe-server.py" "$PORT" &
  mock_pid=$!
  for _ in $(seq 1 50); do
    if curl -s -o /dev/null "http://127.0.0.1:$PORT/200" 2>/dev/null; then break; fi
    sleep 0.1
  done
}
stop_mock() { kill "$mock_pid" 2>/dev/null || true; wait "$mock_pid" 2>/dev/null || true; }

run_admission() {
  DATETIME_REPO_BASE="http://127.0.0.1:$PORT/build/raft/kuiklybase" \
  DATETIME_PUBLISH_MODE="android" \
  MAVEN_VERSION="0.1.0-raft.0" \
  GITHUB_PACKAGES_USERNAME="u" GITHUB_PACKAGES_TOKEN="t" \
  DATETIME_PROBE_BACKOFF_SECONDS=0 DATETIME_PROBE_MAX_ATTEMPTS=2 \
  DATETIME_DRY_RUN="true" \
  bash "$SCRIPT_DIR/publish-github-packages.sh" 2>&1
}

echo "== admission: all artifacts ABSENT (404) -> NONE, will publish =="
start_mock 404
out="$(run_admission)"; rc=$?
stop_mock
check_contains "exit 0" "" "rc=$rc"
check_contains "NONE -> will publish" "datetime-android:0.1.0-raft.0 -> NONE (will publish)" "$out"
check_contains "dry-run publish task" "publishAndroidReleasePublicationToGithubPackagesRepository" "$out"

echo "== admission: all artifacts EXISTS (200) -> COMPLETE, skip =="
start_mock 200
out="$(run_admission)"; rc=$?
stop_mock
check_contains "COMPLETE -> skip" "datetime-android:0.1.0-raft.0 -> COMPLETE (skip)" "$out"
check_contains "ALL_COMPLETE" "ALL_COMPLETE" "$out"

echo "== admission: auth failure (401) -> fail closed =="
start_mock 401
set +e
out="$(run_admission)"; rc=$?
set -e
stop_mock
if [ "$rc" -ne 0 ]; then echo "  OK   non-zero exit ($rc)"; pass=$((pass+1)); else echo "  FAIL expected non-zero exit" >&2; fail=$((fail+1)); fi
check_contains "fail-closed message" "fail closed" "$out"

echo "== result: pass=$pass fail=$fail =="
if [ "$fail" -ne 0 ]; then
  echo "PUBLISH_ADMISSION_SELFTEST_FAIL" >&2
  exit 1
fi
echo "PUBLISH_ADMISSION_SELFTEST_PASS"
