#!/usr/bin/env bash
#
# Stage DatetimeKMM publications for the Raft-only lane (Raft task #106).
#
# One shard job runs this per mode: Gradle writes the mode's publications into
# a task-scoped file Maven repository (DATETIME_STAGING_DIR) that must start
# empty -- never mavenLocal, never a shared dir -- and raft-publish.py then
# generates the shard authority manifest from that staging directory only
# (exact-set equality, local aux excluded, POM sourceSha cross-binding).
#
# This script NEVER talks to Raft and needs no credentials: the aggregate
# classification (one global plan over the whole version), the create-only
# upload, and the byte readback all run as separate workflow jobs on the
# merged manifest (see publish-datetime-raft.yml), so no global partial state
# can be repaired by a shard.
#
# Env:
#   DATETIME_PUBLISH_MODE   one of: ohos-tree | android | ios | metadata
#   DATETIME_SETTINGS_FILE  settings file for the build tree (ohos-tree uses
#                           settings.ohos.gradle.kts; others use the default)
#   MAVEN_VERSION           required: the version being staged (admission-checked
#                           against gradle.properties by the workflow beforehand)
#   DATETIME_STAGING_DIR    required: task-scoped staging repository dir
#   DATETIME_SOURCE_SHA     required: exact 40-hex dispatch source SHA (POM
#                           provenance cross-binding)
#   DATETIME_DRY_RUN        "true" to print the staging task list and stop
#                           (offline teeth; no Gradle, no staging)

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

# Publications of this mode: <artifact> <version-suffix> <kind> <gradle-task>
TASKS=()
expect_file="$STAGING.expect.txt"
: > "$expect_file"

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
cp "$expect_file" "$WORK_DIR/expect.txt"
rm -f "$expect_file"

python3 scripts/raft-publish.py manifest \
  --staging "$STAGING" \
  --expect "$WORK_DIR/expect.txt" \
  --version "$VERSION" \
  --output "$WORK_DIR/manifest.json"

echo "STAGE_OK mode=$MODE staged manifest=$WORK_DIR/manifest.json"
