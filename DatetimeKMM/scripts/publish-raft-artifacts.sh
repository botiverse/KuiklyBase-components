#!/usr/bin/env bash
#
# Publish DatetimeKMM publications to Raft Artifacts (Raft-only lane, task #106).
#
# Flow per mode (state machine frozen by review):
#   1. stage: Gradle writes the mode's publications into a task-scoped file
#      Maven repository (DATETIME_STAGING_DIR) that this script requires to
#      exist and be empty -- never mavenLocal, never a shared dir;
#   2. manifest: raft-publish.py hashes the staged bytes and asserts the
#      staging dir contains exactly the expected publication set (no old
#      versions, no maven-metadata.xml, no sidecars) and that every staged
#      POM carries the dispatch sourceSha;
#   3. classify: conflict-first aggregate probe of the Raft side
#      (owned-prefix enumeration + per-path HEAD/GET);
#   4. act: ALL_ABSENT -> create-only PUT of the staged bytes (server 409
#      stops the run, never overwrite); ALL_PRESENT_IDENTICAL -> zero PUTs,
#      but the run still byte-compares every remote file against the staged
#      bytes (verified no-op, not a blind skip); anything else -> fail closed;
#   5. verify: N/N GET+SHA readback plus exact-set re-enumeration, receipt
#      cross-binding version + source exact + prefix.
#
# Credentials: RAFT_ARTIFACTS_USERNAME / RAFT_ARTIFACTS_PUBLISH_TOKEN must be
# present before any network use; a release dispatch without the task-minted
# token fails closed here (as proven in run 31252712805).
#
# Env:
#   DATETIME_PUBLISH_MODE   one of: ohos-tree | android | ios | metadata
#   DATETIME_SETTINGS_FILE  settings file for the build tree (ohos-tree uses
#                           settings.ohos.gradle.kts; others use the default)
#   MAVEN_VERSION           required: the version to publish (admission-checked
#                           against gradle.properties by the workflow beforehand)
#   DATETIME_STAGING_DIR    required: task-scoped empty staging repository dir
#   DATETIME_SOURCE_SHA     required: exact 40-hex dispatch source SHA (POM
#                           provenance cross-binding)
#   DATETIME_DRY_RUN        "true" to stage + manifest + print without any
#                           network use or upload

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=publish-lib.sh
source "$SCRIPT_DIR/publish-lib.sh"
cd "$PROJECT_DIR"

MODE="${DATETIME_PUBLISH_MODE:?DATETIME_PUBLISH_MODE is required}"
VERSION="${MAVEN_VERSION:?MAVEN_VERSION is required}"
STAGING="${DATETIME_STAGING_DIR:?DATETIME_STAGING_DIR is required}"
DATETIME_DRY_RUN="${DATETIME_DRY_RUN:-false}"
REPO_HOST="https://maven.artifacts.botiverse.dev"
REPO_BASE="${REPO_HOST}/build/raft/kuiklybase"

[ -d "$STAGING" ] || mkdir -p "$STAGING"
if [ -n "$(ls -A "$STAGING")" ]; then
  echo "staging dir is not empty: $STAGING (task-scoped dirs must start empty)" >&2
  exit 1
fi

if [[ "$DATETIME_DRY_RUN" != "true" ]]; then
  if [[ -z "${RAFT_ARTIFACTS_USERNAME:-}" || -z "${RAFT_ARTIFACTS_PUBLISH_TOKEN:-}" ]]; then
    echo "Raft Artifacts credentials are required (task-minted scoped token)." >&2
    exit 1
  fi
fi

# Publications of this mode: <artifact> <version-suffix> <kind> <gradle-task>
TASKS=()
PUB_COUNT=0
expect_file="$(mktemp)"
trap 'rm -f "$expect_file"' EXIT

add_publication() {
  local artifact="$1" version="$2" kind="$3" task="$4"
  local url rel
  while IFS= read -r url; do
    rel="${url#"$REPO_HOST"/}"
    if [ "$rel" = "$url" ]; then
      echo "publication URL outside the Raft host: $url" >&2
      exit 1
    fi
    printf '%s\n' "$rel" >> "$expect_file"
  done < <(publication_urls "$REPO_BASE" "$artifact" "$version" "$kind")
  TASKS+=("$task")
  PUB_COUNT=$(( PUB_COUNT + 1 ))
}

case "$MODE" in
  ohos-tree)
    DATETIME_SETTINGS_FILE="${DATETIME_SETTINGS_FILE:-settings.ohos.gradle.kts}"
    add_publication "datetime-ohosarm64" "${VERSION}-ohos" "native-ohos" \
      ":datetime:publishOhosArm64PublicationToStagingRepository"
    add_publication "datetime" "${VERSION}-ohos" "root-metadata" \
      ":datetime:publishKotlinMultiplatformPublicationToStagingRepository"
    ;;
  android)
    add_publication "datetime-android" "${VERSION}" "android" \
      ":datetime:publishAndroidReleasePublicationToStagingRepository"
    ;;
  ios)
    add_publication "datetime-iosx64" "${VERSION}" "native" \
      ":datetime:publishIosX64PublicationToStagingRepository"
    add_publication "datetime-iosarm64" "${VERSION}" "native" \
      ":datetime:publishIosArm64PublicationToStagingRepository"
    add_publication "datetime-iossimulatorarm64" "${VERSION}" "native" \
      ":datetime:publishIosSimulatorArm64PublicationToStagingRepository"
    ;;
  metadata)
    add_publication "datetime" "${VERSION}" "root-metadata" \
      ":datetime:publishKotlinMultiplatformPublicationToStagingRepository"
    ;;
  *)
    echo "Unknown DATETIME_PUBLISH_MODE: $MODE" >&2
    exit 2
    ;;
esac

gradle_args=("--no-daemon" "--console=plain" "--stacktrace" "-PmavenVersion=$VERSION")
if [[ -n "${DATETIME_SETTINGS_FILE:-}" ]]; then
  gradle_args+=("-c" "$DATETIME_SETTINGS_FILE")
fi

if [[ "$DATETIME_DRY_RUN" == "true" ]]; then
  echo "DRY RUN staging tasks: ${TASKS[*]} (settings=${DATETIME_SETTINGS_FILE:-default})"
  exit 0
fi

echo "== staging publications for mode '$MODE' into $STAGING =="
./gradlew "${gradle_args[@]}" "${TASKS[@]}"

WORK_DIR="$STAGING.work"
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR"
MANIFEST="$WORK_DIR/manifest.json"
PLAN="$WORK_DIR/plan.json"
RECEIPT="$WORK_DIR/publish-receipt.json"

python3 scripts/raft-publish.py manifest \
  --staging "$STAGING" \
  --expect "$expect_file" \
  --version "$VERSION" \
  --output "$MANIFEST"

python3 scripts/raft-publish.py classify --manifest "$MANIFEST" --output "$PLAN"

decision="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["decision"])' "$PLAN")"
published_now=0
case "$decision" in
  publish)
    python3 scripts/raft-publish.py publish --manifest "$MANIFEST" --staging "$STAGING" --plan "$PLAN"
    published_now="$PUB_COUNT"
    ;;
  noop-verified)
    echo "all ${PUB_COUNT} publication(s) already present with identical bytes; zero PUTs (verified no-op)"
    ;;
  *)
    echo "unexpected plan decision: $decision" >&2
    exit 1
    ;;
esac

# The readback runs on both decisions: it is the terminal proof for a fresh
# publish AND for a verified no-op.
python3 scripts/raft-publish.py verify --manifest "$MANIFEST" --output "$RECEIPT"

# Newly-published publication count feeds the workflow's stale-rerun guard:
# a run where every job was a no-op fails there, forcing a version bump.
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  echo "published_count=$published_now" >> "$GITHUB_OUTPUT"
fi

echo "PUBLISH_OK mode=$MODE decision=$decision publications=$PUB_COUNT published_now=$published_now receipt=$RECEIPT"
