#!/usr/bin/env bash
#
# Publish DatetimeKMM publications to GitHub Packages.
#
# The DatetimeKMM Gradle build reads the repository coordinate from
# GITHUB_REPOSITORY (default bytemain/KuiklyBase-components) and the
# credentials from GITHUB_PACKAGES_USERNAME/GITHUB_PACKAGES_TOKEN (falling back
# to GITHUB_ACTOR/GITHUB_TOKEN), so this script only selects the publish tasks
# and the build tree. It does not take credentials as arguments.
#
# Env:
#   DATETIME_SETTINGS_FILE  optional settings file for the build tree
#                           (e.g. settings.ohos.gradle.kts for the OHOS/KBA tree;
#                           default = normal Android/iOS tree)
#   DATETIME_PUBLISH_TASKS  space-separated publish tasks to run (default = all
#                           normal-tree publications)
#   MAVEN_VERSION           optional -PmavenVersion override
#   DATETIME_DRY_RUN        "true" to print the gradle command without running

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

GITHUB_PACKAGES_USERNAME="${GITHUB_PACKAGES_USERNAME:-${GITHUB_ACTOR:-}}"
GITHUB_PACKAGES_TOKEN="${GITHUB_PACKAGES_TOKEN:-${GITHUB_TOKEN:-${GH_TOKEN:-}}}"

if [[ -z "$GITHUB_PACKAGES_USERNAME" ]]; then
  echo "GITHUB_PACKAGES_USERNAME or GITHUB_ACTOR is required." >&2
  exit 1
fi
if [[ -z "$GITHUB_PACKAGES_TOKEN" ]]; then
  echo "GITHUB_PACKAGES_TOKEN, GITHUB_TOKEN, or GH_TOKEN is required." >&2
  exit 1
fi
export GITHUB_PACKAGES_USERNAME GITHUB_PACKAGES_TOKEN

default_publish_tasks=(
  ":datetime:publishAndroidReleasePublicationToGithubPackagesRepository"
  ":datetime:publishIosX64PublicationToGithubPackagesRepository"
  ":datetime:publishIosArm64PublicationToGithubPackagesRepository"
  ":datetime:publishIosSimulatorArm64PublicationToGithubPackagesRepository"
  ":datetime:publishKotlinMultiplatformPublicationToGithubPackagesRepository"
)
DATETIME_PUBLISH_TASKS="${DATETIME_PUBLISH_TASKS:-${default_publish_tasks[*]}}"
IFS=' ' read -r -a publish_tasks <<< "$DATETIME_PUBLISH_TASKS"
DATETIME_DRY_RUN="${DATETIME_DRY_RUN:-false}"

gradle_args=("--no-daemon" "--console=plain" "--stacktrace")

# Dual-tree: select the OHOS/KBA build tree when DATETIME_SETTINGS_FILE is set.
if [[ -n "${DATETIME_SETTINGS_FILE:-}" ]]; then
  gradle_args+=("-c" "$DATETIME_SETTINGS_FILE")
fi

if [[ -n "${MAVEN_VERSION:-}" ]]; then
  gradle_args+=("-PmavenVersion=$MAVEN_VERSION")
fi

# Fail fast if a requested publish task does not exist in the selected tree.
task_cache_dir="$(mktemp -d)"
trap 'rm -rf "$task_cache_dir"' EXIT
task_exists() {
  local task_path="$1"
  local project_path="${task_path%:*}"
  local task_name="${task_path##*:}"
  local cache_name="${project_path//:/_}"
  local task_file="$task_cache_dir/${cache_name:-root}.tasks"
  if [[ ! -f "$task_file" ]]; then
    local discover_args=(--no-daemon --console=plain)
    if [[ -n "${DATETIME_SETTINGS_FILE:-}" ]]; then
      discover_args+=("-c" "$DATETIME_SETTINGS_FILE")
    fi
    if ! ./gradlew "${discover_args[@]}" "$project_path:tasks" --all > "$task_file" 2>&1; then
      cat "$task_file" >&2
      return 1
    fi
  fi
  grep -qE "^${task_name}( |$)" "$task_file"
}

for task in "${publish_tasks[@]}"; do
  if ! task_exists "$task"; then
    echo "Publish task not found in selected tree: $task" >&2
    exit 1
  fi
done

cmd=(./gradlew "${gradle_args[@]}" "${publish_tasks[@]}")
if [[ "$DATETIME_DRY_RUN" == "true" ]]; then
  echo "DRY RUN: ${cmd[*]}"
  exit 0
fi
echo "Publishing: ${publish_tasks[*]} (settings=${DATETIME_SETTINGS_FILE:-default})"
"${cmd[@]}"
echo "PUBLISH_OK"
