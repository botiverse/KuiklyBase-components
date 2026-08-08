#!/usr/bin/env bash
#
# Offline admission teeth for scripts/publish-raft-artifacts.sh (Raft-only
# lane). No Gradle, no network: exercises the fail-closed environment and mode
# contract and the dry-run task mapping. The state machine itself is covered
# by test-raft-publish.py against a fake transport.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

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
check_rc() {
  local label="$1" want="$2" got="$3"
  if [ "$want" = "$got" ]; then
    echo "  OK   $label"
    pass=$(( pass + 1 ))
  else
    echo "  FAIL $label (want rc=$want got rc=$got)" >&2
    fail=$(( fail + 1 ))
  fi
}

# run_lane VAR=value ... -- a minimal, explicit environment (no inherited
# RAFT_ARTIFACTS_* / DATETIME_* leakage from the caller).
run_lane() {
  env -i PATH="$PATH" HOME="$HOME" DATETIME_STAGING_DIR="$TSTAGING" "$@" \
    bash "$SCRIPT_DIR/publish-raft-artifacts.sh" 2>&1
}

TSTAGING="$(mktemp -d)"
trap 'rm -rf "$TSTAGING"' EXIT

echo "== missing credentials fail closed before anything =="
set +e
out="$(run_lane DATETIME_PUBLISH_MODE=android MAVEN_VERSION=9.9.9-raft.9)"; rc=$?
set -e
check_rc "non-zero exit" "1" "$rc"
check_contains "credential message" "Raft Artifacts credentials are required" "$out"

echo "== non-empty staging fails closed =="
touch "$TSTAGING/stray"
set +e
out="$(run_lane DATETIME_PUBLISH_MODE=android MAVEN_VERSION=9.9.9-raft.9 \
  RAFT_ARTIFACTS_USERNAME=raft-ci RAFT_ARTIFACTS_PUBLISH_TOKEN=t)"; rc=$?
set -e
check_rc "non-zero exit" "1" "$rc"
check_contains "staging message" "staging dir is not empty" "$out"
rm -f "$TSTAGING/stray"

echo "== unknown mode rejected =="
set +e
out="$(run_lane DATETIME_PUBLISH_MODE=bogus MAVEN_VERSION=9.9.9-raft.9 \
  RAFT_ARTIFACTS_USERNAME=raft-ci RAFT_ARTIFACTS_PUBLISH_TOKEN=t)"; rc=$?
set -e
check_rc "exit 2" "2" "$rc"
check_contains "unknown mode message" "Unknown DATETIME_PUBLISH_MODE" "$out"

echo "== dry-run maps modes to staging tasks (no gradle, no network) =="
out="$(run_lane DATETIME_PUBLISH_MODE=android MAVEN_VERSION=9.9.9-raft.9 DATETIME_DRY_RUN=true)"
check_contains "android task" "publishAndroidReleasePublicationToStagingRepository" "$out"
out="$(run_lane DATETIME_PUBLISH_MODE=metadata MAVEN_VERSION=9.9.9-raft.9 DATETIME_DRY_RUN=true)"
check_contains "metadata task" "publishKotlinMultiplatformPublicationToStagingRepository" "$out"
out="$(run_lane DATETIME_PUBLISH_MODE=ios MAVEN_VERSION=9.9.9-raft.9 DATETIME_DRY_RUN=true)"
check_contains "ios x64 task" "publishIosX64PublicationToStagingRepository" "$out"
check_contains "ios sim task" "publishIosSimulatorArm64PublicationToStagingRepository" "$out"
out="$(run_lane DATETIME_PUBLISH_MODE=ohos-tree MAVEN_VERSION=9.9.9-raft.9 DATETIME_DRY_RUN=true)"
check_contains "ohos task" "publishOhosArm64PublicationToStagingRepository" "$out"
check_contains "ohos settings" "settings.ohos.gradle.kts" "$out"

echo "== dry-run output never references a remote/github repository task =="
out="$(run_lane DATETIME_PUBLISH_MODE=ios MAVEN_VERSION=9.9.9-raft.9 DATETIME_DRY_RUN=true)"
if printf '%s' "$out" | grep -qE "To(GithubPackages|RaftArtifacts)Repository"; then
  echo "  FAIL remote repository task leaked into dry-run" >&2
  fail=$(( fail + 1 ))
else
  echo "  OK   no remote repository task in dry-run"
  pass=$(( pass + 1 ))
fi

echo "== result: pass=$pass fail=$fail =="
if [ "$fail" -ne 0 ]; then
  echo "RAFT_PUBLISH_ADMISSION_TEETH_FAIL" >&2
  exit 1
fi
echo "RAFT_PUBLISH_ADMISSION_TEETH_PASS"
