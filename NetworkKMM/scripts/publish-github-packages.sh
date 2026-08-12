#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${NETWORK_PROJECT_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
PROJECT_DIR="$(cd "$PROJECT_DIR" && pwd)"
REPOSITORY_DIR="$(cd "$PROJECT_DIR/.." && pwd)"
source "$SCRIPT_DIR/network-publication-manifest.sh"

cd "$PROJECT_DIR"

GITHUB_PACKAGES_USERNAME="${GITHUB_PACKAGES_USERNAME:-${GITHUB_ACTOR:-}}"
GITHUB_PACKAGES_TOKEN="${GITHUB_PACKAGES_TOKEN:-${GITHUB_TOKEN:-${GH_TOKEN:-}}}"
export GITHUB_PACKAGES_USERNAME GITHUB_PACKAGES_TOKEN

if [[ -z "$GITHUB_PACKAGES_USERNAME" ]]; then
  echo "GITHUB_PACKAGES_USERNAME or GITHUB_ACTOR is required." >&2
  exit 1
fi
if [[ -z "$GITHUB_PACKAGES_TOKEN" ]]; then
  echo "GITHUB_PACKAGES_TOKEN, GITHUB_TOKEN, or GH_TOKEN is required." >&2
  exit 1
fi

# actions/checkout writes safe.directory into a temporary HOME that does not
# survive into container steps. Keep the exception scoped to this process
# instead of mutating a developer's or runner's real global Git configuration.
git_config_home="$(mktemp -d)"
chmod 700 "$git_config_home"
task_cache_dir=""
cleanup() {
  if [[ -n "$task_cache_dir" ]]; then
    rm -rf "$task_cache_dir"
  fi
  rm -rf "$git_config_home"
}
trap cleanup EXIT
HOME="$git_config_home" git config --global --add safe.directory "$REPOSITORY_DIR"

publication_git() {
  HOME="$git_config_home" git "$@"
}

# Bind provenance to the bytes in this checkout, never to event metadata. A
# dirty tree cannot be represented by a commit SHA and is therefore not a
# releasable source state.
if [[ -n "$(publication_git status --porcelain=v1 --untracked-files=all)" ]]; then
  echo "NetworkKMM publication requires a clean git checkout." >&2
  exit 1
fi
resolved_source_sha="$(publication_git -C "$PROJECT_DIR" rev-parse HEAD)"
if [[ ! "$resolved_source_sha" =~ ^[0-9a-f]{40}$ ]]; then
  echo "git rev-parse HEAD did not return an exact lowercase commit SHA." >&2
  exit 1
fi
if [[ -n "${NETWORK_ARTIFACT_SOURCE_SHA:-}" && "$NETWORK_ARTIFACT_SOURCE_SHA" != "$resolved_source_sha" ]]; then
  echo "Caller-provided NETWORK_ARTIFACT_SOURCE_SHA does not match the artifact-source checkout." >&2
  exit 1
fi
if [[ -n "${NETWORK_SOURCE_SHA:-}" && "$NETWORK_SOURCE_SHA" != "$resolved_source_sha" ]]; then
  echo "Caller-provided NETWORK_SOURCE_SHA does not match git rev-parse HEAD." >&2
  exit 1
fi
NETWORK_SOURCE_SHA="$resolved_source_sha"
export NETWORK_SOURCE_SHA

base_version="${MAVEN_VERSION:-}"
if [[ -z "$base_version" ]]; then
  base_version="$(sed -n 's/^mavenVersion=//p' gradle.properties | tail -n 1)"
fi
if [[ -z "$base_version" || "$base_version" == *SNAPSHOT* || "$base_version" == *-ohos ]]; then
  echo "MAVEN_VERSION must be a non-SNAPSHOT base version without the -ohos suffix." >&2
  exit 1
fi
MAVEN_VERSION="$base_version"
export MAVEN_VERSION

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
required_task_text="${NETWORK_REQUIRED_TASKS:-$DEFAULT_NETWORK_PUBLISH_TASKS}"
IFS=' ' read -r -a required_tasks <<< "$required_task_text"

array_contains() {
  local needle="$1"
  shift
  local candidate
  for candidate in "$@"; do
    if [[ "$candidate" == "$needle" ]]; then
      return 0
    fi
  done
  return 1
}

validated_required_tasks=()
for task in "${required_tasks[@]}"; do
  network_assert_known_publication_task "$task"
  # Bash 3.2 treats an empty array as unset under `set -u`; the guarded
  # expansion keeps the first iteration portable to the macOS system Bash.
  if array_contains "$task" ${validated_required_tasks[@]+"${validated_required_tasks[@]}"}; then
    echo "Duplicate required publication task: $task" >&2
    exit 1
  fi
  validated_required_tasks+=("$task")
done
required_tasks=(${validated_required_tasks[@]+"${validated_required_tasks[@]}"})

if [[ "${NETWORK_GITHUB_PUBLISH_TASKS+x}" == "x" ]]; then
  IFS=' ' read -r -a github_publish_tasks <<< "$NETWORK_GITHUB_PUBLISH_TASKS"
else
  github_publish_tasks=("${required_tasks[@]}")
fi
selected_publish_tasks=()
for task in ${github_publish_tasks[@]+"${github_publish_tasks[@]}"}; do
  [[ -n "$task" ]] || continue
  network_assert_known_publication_task "$task"
  if ! array_contains "$task" "${required_tasks[@]}"; then
    echo "GitHub publication task is outside the required lane: $task" >&2
    exit 1
  fi
  selected_publish_tasks+=("$task")
done
if (( ${#selected_publish_tasks[@]} == 0 )); then
  echo "The immutable-state plan selected no publication tasks." >&2
  exit 1
fi

NETWORK_REQUIRE_TASKS="${NETWORK_REQUIRE_TASKS:-false}"
NETWORK_DRY_RUN="${NETWORK_DRY_RUN:-false}"
gradle_args=(
  "--no-daemon"
  "--console=plain"
  "-PnetworkSourceSha=$NETWORK_SOURCE_SHA"
  "-PmavenVersion=$MAVEN_VERSION"
)

# Dual-tree support (task #18): NETWORK_SETTINGS_FILE selects the build tree
# (e.g. settings.ohos.gradle.kts for the OHOS/KBA tree). Default = normal tree.
if [[ -n "${NETWORK_SETTINGS_FILE:-}" ]]; then
  gradle_args+=("-c" "$NETWORK_SETTINGS_FILE")
fi
if [[ -n "${GITHUB_PACKAGES_OWNER:-}" ]]; then
  gradle_args+=("-PgithubPackagesOwner=$GITHUB_PACKAGES_OWNER")
fi
if [[ -n "${GITHUB_PACKAGES_REPOSITORY:-}" ]]; then
  gradle_args+=("-PgithubPackagesRepository=$GITHUB_PACKAGES_REPOSITORY")
fi

task_cache_dir="$(mktemp -d)"

task_exists() {
  local task_path="$1"
  local project_path="${task_path%:*}"
  local task_name="${task_path##*:}"
  local cache_name="${project_path//:/_}"
  local task_file="$task_cache_dir/${cache_name:-root}.tasks"

  if [[ ! -f "$task_file" ]]; then
    local discover_args=(--no-daemon --console=plain "-PnetworkSourceSha=$NETWORK_SOURCE_SHA")
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
for publish_task in ${selected_publish_tasks[@]+"${selected_publish_tasks[@]}"}; do
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

echo "Publishing NetworkKMM authority publications to GitHub Packages:"
printf '  %s\n' "${available_publish_tasks[@]}"

verify_android_curl_runtime=false
for publish_task in "${available_publish_tasks[@]}"; do
  if [[ "$publish_task" == ":network-android-curl-runtime:publishAndroidCurlRuntimePublicationToGithubPackagesRepository" \
    ]]; then
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
