#!/usr/bin/env bash
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

default_publish_tasks=(
  ":network:publishAndroidPublicationToGithubPackagesRepository"
  ":network-android-curl-runtime:publishAndroidCurlRuntimePublicationToGithubPackagesRepository"
  ":network:publishIosX64PublicationToGithubPackagesRepository"
  ":network:publishIosArm64PublicationToGithubPackagesRepository"
  ":network:publishIosSimulatorArm64PublicationToGithubPackagesRepository"
  ":network:publishOhosArm64PublicationToGithubPackagesRepository"
  ":network-ohos-runtime:publishAllPublicationsToGithubPackagesRepository"
  ":network-ohos-runtime-gradle-plugin:publishAllPublicationsToGithubPackagesRepository"
  ":network:publishKotlinMultiplatformPublicationToGithubPackagesRepository"
)
DEFAULT_NETWORK_PUBLISH_TASKS="${default_publish_tasks[*]}"
NETWORK_PUBLISH_TASKS="${NETWORK_PUBLISH_TASKS:-$DEFAULT_NETWORK_PUBLISH_TASKS}"
IFS=' ' read -r -a publish_tasks <<< "$NETWORK_PUBLISH_TASKS"
NETWORK_REQUIRE_TASKS="${NETWORK_REQUIRE_TASKS:-false}"
NETWORK_DRY_RUN="${NETWORK_DRY_RUN:-false}"

gradle_args=(
  "--no-daemon"
  "--console=plain"
  "-PgithubPackagesUsername=$GITHUB_PACKAGES_USERNAME"
  "-PgithubPackagesToken=$GITHUB_PACKAGES_TOKEN"
)

# Dual-tree support (task #18): NETWORK_SETTINGS_FILE selects the build tree
# (e.g. settings.ohos.gradle.kts for the OHOS/KBA tree). Default = normal tree.
if [[ -n "${NETWORK_SETTINGS_FILE:-}" ]]; then
  gradle_args+=("-c" "$NETWORK_SETTINGS_FILE")
fi

if [[ -n "${MAVEN_VERSION:-}" ]]; then
  gradle_args+=("-PmavenVersion=$MAVEN_VERSION")
fi

if [[ -n "${GITHUB_PACKAGES_OWNER:-}" ]]; then
  gradle_args+=("-PgithubPackagesOwner=$GITHUB_PACKAGES_OWNER")
fi

if [[ -n "${GITHUB_PACKAGES_REPOSITORY:-}" ]]; then
  gradle_args+=("-PgithubPackagesRepository=$GITHUB_PACKAGES_REPOSITORY")
fi

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
    if [[ -n "${NETWORK_SETTINGS_FILE:-}" ]]; then
      discover_args+=("-c" "$NETWORK_SETTINGS_FILE")
    fi
    if ! ./gradlew "${discover_args[@]}" "$project_path:tasks" --all > "$task_file" 2>&1; then
      cat "$task_file" >&2
      return 2
    fi
  fi

  grep -Eq "^[[:space:]]*$task_name([[:space:]]|$|-)" "$task_file"
}

available_publish_tasks=()
missing_publish_tasks=()
for publish_task in "${publish_tasks[@]}"; do
  if task_exists "$publish_task"; then
    available_publish_tasks+=("$publish_task")
  else
    task_status=$?
    if [[ "$task_status" -eq 2 ]]; then
      echo "Unable to discover Gradle publish tasks." >&2
      exit 1
    fi
    missing_publish_tasks+=("$publish_task")
  fi
done

if (( ${#missing_publish_tasks[@]} > 0 )); then
  if [[ "$NETWORK_REQUIRE_TASKS" == "true" ]]; then
    echo "Missing required publish tasks on this host:" >&2
    printf '  %s\n' "${missing_publish_tasks[@]}" >&2
    exit 1
  fi

  echo "Skipping publish tasks unavailable on this host:"
  printf '  %s\n' "${missing_publish_tasks[@]}"
fi

if (( ${#available_publish_tasks[@]} == 0 )); then
  echo "No publish tasks are available on this host." >&2
  exit 1
fi

echo "Publishing NetworkKMM publications to GitHub Packages:"
printf '  %s\n' "${available_publish_tasks[@]}"

verify_android_curl_runtime=false
for publish_task in "${available_publish_tasks[@]}"; do
  if [[ "$publish_task" == ":network-android-curl-runtime:publishAndroidCurlRuntimePublicationToGithubPackagesRepository" ]]; then
    verify_android_curl_runtime=true
    break
  fi
done

if [[ "$verify_android_curl_runtime" == "true" && "$NETWORK_DRY_RUN" != "true" ]]; then
  ./gradlew "${gradle_args[@]}" \
    :network:assembleRelease \
    :network-android-curl-runtime:assembleRelease
  ./scripts/verify-android-curl-runtime-aar.sh
fi

if [[ "$NETWORK_DRY_RUN" == "true" ]]; then
  ./gradlew "${gradle_args[@]}" --dry-run "${available_publish_tasks[@]}"
else
  ./gradlew "${gradle_args[@]}" "${available_publish_tasks[@]}"
fi
