#!/usr/bin/env bash
#
# Publish DatetimeKMM publications to GitHub Packages with fail-closed admission.
#
# For every publication this script classifies the full expected-artifact set as
# NONE (publish), COMPLETE (skip), or PARTIAL (hard fail / require a version
# bump), using the shared classifier in publish-lib.sh. It never publishes on an
# inconclusive probe, never treats one representative file as a complete
# publication, and never re-publishes an immutable artifact that already exists.
#
# The Gradle build reads the repository coordinate from GITHUB_REPOSITORY and the
# credentials from GITHUB_PACKAGES_USERNAME/GITHUB_PACKAGES_TOKEN (fallback
# GITHUB_ACTOR/GITHUB_TOKEN).
#
# Env:
#   DATETIME_PUBLISH_MODE   one of: ohos-tree | android | ios | metadata
#   DATETIME_SETTINGS_FILE  settings file for the build tree (ohos-tree uses
#                           settings.ohos.gradle.kts; others use the default)
#   MAVEN_VERSION           required: the version to publish (admission-checked
#                           against gradle.properties by the workflow beforehand)
#   DATETIME_DRY_RUN        "true" to classify + print tasks without publishing

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=publish-lib.sh
source "$SCRIPT_DIR/publish-lib.sh"
cd "$PROJECT_DIR"

GITHUB_PACKAGES_USERNAME="${GITHUB_PACKAGES_USERNAME:-${GITHUB_ACTOR:-}}"
GITHUB_PACKAGES_TOKEN="${GITHUB_PACKAGES_TOKEN:-${GITHUB_TOKEN:-${GH_TOKEN:-}}}"
GITHUB_REPOSITORY="${GITHUB_REPOSITORY:-bytemain/KuiklyBase-components}"
MODE="${DATETIME_PUBLISH_MODE:?DATETIME_PUBLISH_MODE is required}"
VERSION="${MAVEN_VERSION:?MAVEN_VERSION is required}"
DATETIME_DRY_RUN="${DATETIME_DRY_RUN:-false}"

if [[ -z "$GITHUB_PACKAGES_USERNAME" || -z "$GITHUB_PACKAGES_TOKEN" ]]; then
  echo "GitHub Packages credentials are required." >&2
  exit 1
fi

# DATETIME_REPO_BASE overrides the Maven repository base (self-tests only).
REPO_BASE="${DATETIME_REPO_BASE:-https://maven.pkg.github.com/${GITHUB_REPOSITORY}/build/raft/kuiklybase}"

# publication_urls <artifact> <version> <main-ext>
# Prints the full expected-artifact URL set for one Maven publication: the main
# file, its POM, Gradle module metadata, and sources JAR.
publication_urls() {
  local artifact="$1" version="$2" ext="$3"
  local base="${REPO_BASE}/${artifact}/${version}"
  echo "${base}/${artifact}-${version}.${ext}"
  echo "${base}/${artifact}-${version}.pom"
  echo "${base}/${artifact}-${version}.module"
  echo "${base}/${artifact}-${version}-sources.jar"
}

# admit_publication <artifact> <version> <main-ext> <publish-task>
# Classifies the publication and, if NONE, appends its publish task to RUN_TASKS.
# COMPLETE -> skip. PARTIAL or probe error -> hard fail.
RUN_TASKS=()
SKIPPED=()
admit_publication() {
  local artifact="$1" version="$2" ext="$3" task="$4"
  local urls=() line verdict
  # Bash 3.2 compatible (macOS system bash): no mapfile.
  while IFS= read -r line; do urls+=("$line"); done < <(publication_urls "$artifact" "$version" "$ext")
  verdict="$(classify_manifest "$GITHUB_PACKAGES_USERNAME" "$GITHUB_PACKAGES_TOKEN" "${urls[@]}")" || {
    echo "ADMISSION FAIL: inconclusive probe for $artifact:$version (fail closed)" >&2
    exit 1
  }
  case "$verdict" in
    NONE)
      echo "  $artifact:$version -> NONE (will publish)"
      RUN_TASKS+=("$task")
      ;;
    COMPLETE)
      echo "  $artifact:$version -> COMPLETE (skip)"
      SKIPPED+=("$artifact")
      ;;
    PARTIAL)
      echo "ADMISSION FAIL: $artifact:$version is PARTIAL — some artifacts exist." >&2
      echo "  GitHub Packages versions are immutable; bump mavenVersion to ship new content." >&2
      exit 1
      ;;
    *)
      echo "ADMISSION FAIL: unexpected verdict $verdict for $artifact:$version" >&2
      exit 1
      ;;
  esac
}

case "$MODE" in
  ohos-tree)
    DATETIME_SETTINGS_FILE="${DATETIME_SETTINGS_FILE:-settings.ohos.gradle.kts}"
    admit_publication "datetime-ohosarm64" "${VERSION}-ohos" "klib" \
      ":datetime:publishOhosArm64PublicationToGithubPackagesRepository"
    admit_publication "datetime" "${VERSION}-ohos" "jar" \
      ":datetime:publishKotlinMultiplatformPublicationToGithubPackagesRepository"
    ;;
  android)
    admit_publication "datetime-android" "${VERSION}" "aar" \
      ":datetime:publishAndroidReleasePublicationToGithubPackagesRepository"
    ;;
  ios)
    admit_publication "datetime-iosx64" "${VERSION}" "klib" \
      ":datetime:publishIosX64PublicationToGithubPackagesRepository"
    admit_publication "datetime-iosarm64" "${VERSION}" "klib" \
      ":datetime:publishIosArm64PublicationToGithubPackagesRepository"
    admit_publication "datetime-iossimulatorarm64" "${VERSION}" "klib" \
      ":datetime:publishIosSimulatorArm64PublicationToGithubPackagesRepository"
    ;;
  metadata)
    admit_publication "datetime" "${VERSION}" "jar" \
      ":datetime:publishKotlinMultiplatformPublicationToGithubPackagesRepository"
    ;;
  *)
    echo "Unknown DATETIME_PUBLISH_MODE: $MODE" >&2
    exit 2
    ;;
esac

if [[ "${#RUN_TASKS[@]}" -eq 0 ]]; then
  echo "ALL_COMPLETE: every publication in mode '$MODE' already exists; nothing to publish."
  exit 0
fi

gradle_args=("--no-daemon" "--console=plain" "--stacktrace" "-PmavenVersion=$VERSION")
if [[ -n "${DATETIME_SETTINGS_FILE:-}" ]]; then
  gradle_args+=("-c" "$DATETIME_SETTINGS_FILE")
fi

if [[ "$DATETIME_DRY_RUN" == "true" ]]; then
  echo "DRY RUN publish tasks: ${RUN_TASKS[*]} (settings=${DATETIME_SETTINGS_FILE:-default})"
  exit 0
fi

echo "Publishing tasks: ${RUN_TASKS[*]} (settings=${DATETIME_SETTINGS_FILE:-default})"
./gradlew "${gradle_args[@]}" "${RUN_TASKS[@]}"
echo "PUBLISH_OK mode=$MODE published=${RUN_TASKS[*]} skipped=${SKIPPED[*]:-none}"
