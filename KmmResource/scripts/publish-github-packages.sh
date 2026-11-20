#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_DIR"

GITHUB_PACKAGES_USERNAME="${GITHUB_PACKAGES_USERNAME:-${GITHUB_ACTOR:-}}"
GITHUB_PACKAGES_TOKEN="${GITHUB_PACKAGES_TOKEN:-${GITHUB_TOKEN:-${GH_TOKEN:-}}}"

# CI stages the selected publications in a file-backed Maven repository.
# A standalone invocation retains the existing GitHub Packages interface.
lane="${KMMRESOURCE_STAGE_LANE:-all}"
publications=()
case "$lane" in
  linux) publications=(Android OhosArm64) ;;
  ios) publications=(IosX64 IosArm64 IosSimulatorArm64) ;;
  metadata) publications=(KotlinMultiplatform) ;;
  all) publications=(Android OhosArm64 IosX64 IosArm64 IosSimulatorArm64 KotlinMultiplatform) ;;
  *) echo "Unknown publication lane: $lane" >&2; exit 1 ;;
esac
publish_tasks=()
if [[ "$lane" == linux || "$lane" == all ]]; then
  publish_tasks+=(":resource-generator:publishAllPublicationsToGithubPackagesRepository")
fi
modules=(resource-core)
if [[ "${KMMRESOURCE_INCLUDE_COMPOSE:-false}" == true ]]; then
  modules+=(resource-compose)
fi
for module in "${modules[@]}"; do
  for publication in "${publications[@]}"; do
    publish_tasks+=(":$module:publish${publication}PublicationToGithubPackagesRepository")
  done
done
if [[ -n "${KMMRESOURCE_PUBLISH_TASKS:-}" ]]; then
  read -r -a publish_tasks <<< "$KMMRESOURCE_PUBLISH_TASKS"
fi
KMMRESOURCE_REQUIRE_TASKS="${KMMRESOURCE_REQUIRE_TASKS:-false}"
KMMRESOURCE_DRY_RUN="${KMMRESOURCE_DRY_RUN:-false}"
gradle_args=(--no-daemon --console=plain -PkmmResourcePublishOnly=true)

if [[ -n "${KMMRESOURCE_STAGING_DIR:-}" ]]; then
  for i in "${!publish_tasks[@]}"; do
    task="${publish_tasks[$i]}"
    case "$task" in
      *ToGithubPackagesRepository) publish_tasks[$i]="${task%ToGithubPackagesRepository}ToReleaseStagingRepository" ;;
      *ToReleaseStagingRepository) ;;
      *) echo "Select a Maven publication task to stage: $task" >&2; exit 1 ;;
    esac
  done
else
  : "${GITHUB_PACKAGES_USERNAME:?GitHub Packages username is required}"
  : "${GITHUB_PACKAGES_TOKEN:?GitHub Packages token is required}"
  export GITHUB_PACKAGES_USERNAME GITHUB_PACKAGES_TOKEN
fi
if [[ "${KMMRESOURCE_INCLUDE_COMPOSE:-false}" == true ]]; then
  gradle_args+=("-PkmmResourcePublishCompose=true")
fi
if [[ -n "${MAVEN_VERSION:-}" ]]; then
  gradle_args+=("-Pversion=$MAVEN_VERSION" "-PmavenVersion=$MAVEN_VERSION")
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
    if ! ./gradlew "${gradle_args[@]}" "$project_path:tasks" --all > "$task_file" 2>&1; then
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
  if [[ "$KMMRESOURCE_REQUIRE_TASKS" == "true" ]]; then
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

echo "Selected KmmResource Maven publications:"
printf '  %s\n' "${available_publish_tasks[@]}"

if [[ "$KMMRESOURCE_DRY_RUN" == "true" ]]; then
  ./gradlew "${gradle_args[@]}" --dry-run "${available_publish_tasks[@]}"
else
  ./gradlew "${gradle_args[@]}" "${available_publish_tasks[@]}"
fi
