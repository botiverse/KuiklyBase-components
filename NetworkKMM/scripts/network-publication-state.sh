#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/network-publication-manifest.sh"

mode="${1:-plan}"
if [[ "$mode" != "plan" && "$mode" != "verify" ]]; then
  echo "Usage: $0 [plan|verify]" >&2
  exit 2
fi

required_task_text="${NETWORK_REQUIRED_TASKS:-${NETWORK_PUBLISH_TASKS:-}}"
if [[ -z "$required_task_text" ]]; then
  echo "NETWORK_REQUIRED_TASKS is required." >&2
  exit 1
fi
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

base_version="${MAVEN_VERSION:-}"
if [[ -z "$base_version" ]]; then
  base_version="$(sed -n 's/^mavenVersion=//p' "$PROJECT_DIR/gradle.properties" | tail -n 1)"
fi
if [[ -z "$base_version" || "$base_version" == *SNAPSHOT* || "$base_version" == *-ohos ]]; then
  echo "MAVEN_VERSION must be a non-SNAPSHOT base version without the -ohos suffix." >&2
  exit 1
fi

github_repository="${GITHUB_REPOSITORY:-}"
github_username="${GITHUB_PACKAGES_USERNAME:-${GITHUB_ACTOR:-}}"
github_token="${GITHUB_PACKAGES_TOKEN:-${GITHUB_TOKEN:-}}"
raft_base_url="${RAFT_ARTIFACTS_URL:-https://maven.artifacts.botiverse.dev}"
raft_base_url="${raft_base_url%/}"
raft_browser_url="${RAFT_ARTIFACTS_BROWSER_URL:-https://artifacts.botiverse.dev}"
raft_browser_url="${raft_browser_url%/}"
if [[ -z "$github_repository" || -z "$github_username" || -z "$github_token" ]]; then
  echo "GITHUB_REPOSITORY and GitHub Packages credentials are required for immutable-state planning." >&2
  exit 1
fi

github_base_url="https://maven.pkg.github.com/$github_repository"
github_netrc="$(mktemp)"
chmod 600 "$github_netrc"
printf 'machine maven.pkg.github.com\nlogin %s\npassword %s\n' \
  "$github_username" "$github_token" > "$github_netrc"

cleanup() {
  if command -v shred >/dev/null 2>&1; then
    shred -u "$github_netrc"
  else
    rm -f "$github_netrc"
  fi
}
trap cleanup EXIT

curl_code() {
  local destination="$1"
  local relative_path="$2"
  local base_url
  local -a auth_args=()
  case "$destination" in
    github)
      base_url="$github_base_url"
      auth_args=(--netrc-file "$github_netrc")
      ;;
    raft)
      base_url="$raft_base_url"
      ;;
    *)
      echo "Unknown publication destination: $destination" >&2
      return 1
      ;;
  esac
  curl --silent --show-error --head --output /dev/null --write-out '%{http_code}' \
    --connect-timeout 15 --max-time 60 --retry 2 --retry-all-errors \
    ${auth_args[@]+"${auth_args[@]}"} "$base_url/$relative_path"
}

assert_positive_controls() {
  local github_control="${NETWORK_GITHUB_POSITIVE_CONTROL_PATH:-com/tencent/kuiklybase/network-android/0.1.0-raft.29/network-android-0.1.0-raft.29.aar}"
  local github_code raft_code
  github_code="$(curl_code github "$github_control")"
  if [[ "$github_code" != "200" ]]; then
    echo "GitHub Packages positive control failed with HTTP $github_code; publication absence results are void." >&2
    exit 1
  fi
  raft_code="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
    --connect-timeout 15 --max-time 60 --retry 2 --retry-all-errors \
    "$raft_browser_url/scopes/com.tencent.kuiklybase")"
  if [[ "$raft_code" != "200" ]]; then
    echo "Raft Artifacts scope positive control failed with HTTP $raft_code; publication absence results are void." >&2
    exit 1
  fi
}

classify_task() {
  local destination="$1"
  local task="$2"
  local exists=0
  local missing=0
  local total=0
  local path code
  while IFS= read -r path; do
    [[ -n "$path" ]] || continue
    total=$((total + 1))
    code="$(curl_code "$destination" "$path")"
    case "$code" in
      200) exists=$((exists + 1)) ;;
      404) missing=$((missing + 1)) ;;
      *)
        echo "$destination probe for $task failed with HTTP $code." >&2
        return 1
        ;;
    esac
  done < <(network_required_paths_for "$task" "$base_version")

  if (( total == 0 )); then
    echo "Publication manifest returned no paths for $task." >&2
    return 1
  fi
  if (( exists == total )); then
    printf 'complete\n'
  elif (( missing == total )); then
    printf 'absent\n'
  else
    echo "$destination has a partial immutable publication for $task ($exists/$total required files present); refusing an unsafe overwrite retry." >&2
    return 1
  fi
}

assert_positive_controls

github_missing=()
raft_missing=()
for task in "${required_tasks[@]}"; do
  github_state="$(classify_task github "$task")"
  raft_state="$(classify_task raft "$task")"
  echo "$task: github=$github_state raft=$raft_state"
  if [[ "$github_state" == "absent" ]]; then
    github_missing+=("$task")
  fi
  if [[ "$raft_state" == "absent" ]]; then
    raft_missing+=("$task")
  fi
done

if [[ "$mode" == "verify" ]]; then
  if (( ${#github_missing[@]} > 0 || ${#raft_missing[@]} > 0 )); then
    echo "Dual publication did not converge: github_missing=${#github_missing[@]} raft_missing=${#raft_missing[@]}." >&2
    exit 1
  fi
  echo "All required NetworkKMM publication files exist in both repositories."
  exit 0
fi

missing_union=()
for task in "${required_tasks[@]}"; do
  if array_contains "$task" ${github_missing[@]+"${github_missing[@]}"} \
    || array_contains "$task" ${raft_missing[@]+"${raft_missing[@]}"}; then
    missing_union+=("$task")
  fi
done

# The legacy workflow inputs remain available for a deliberate partial retry,
# but they are an exact allowlist, not a way to omit a missing publication.
if [[ -n "${NETWORK_PUBLISH_TASKS:-}" ]]; then
  IFS=' ' read -r -a requested_tasks <<< "$NETWORK_PUBLISH_TASKS"
  requested_lane_tasks=()
  for task in "${requested_tasks[@]}"; do
    network_assert_known_publication_task "$task"
    # android_ohos_publish_tasks is shared by two jobs. Each job validates
    # only the entries belonging to its own required lane.
    if array_contains "$task" "${required_tasks[@]}"; then
      if array_contains "$task" ${requested_lane_tasks[@]+"${requested_lane_tasks[@]}"}; then
        echo "Requested retry contains a duplicate publication task: $task" >&2
        exit 1
      fi
      requested_lane_tasks+=("$task")
    fi
  done
  if (( ${#requested_lane_tasks[@]} != ${#missing_union[@]} )); then
    echo "Requested retry tasks must exactly match all missing publications in this lane." >&2
    exit 1
  fi
  for task in ${missing_union[@]+"${missing_union[@]}"}; do
    if ! array_contains "$task" ${requested_lane_tasks[@]+"${requested_lane_tasks[@]}"}; then
      echo "Requested retry omits missing publication task: $task" >&2
      exit 1
    fi
  done
fi

github_missing_text=""
raft_missing_text=""
if (( ${#github_missing[@]} > 0 )); then
  github_missing_text="${github_missing[*]}"
fi
if (( ${#raft_missing[@]} > 0 )); then
  raft_missing_text="${raft_missing[*]}"
fi
skip_publish=false
if (( ${#missing_union[@]} == 0 )); then
  skip_publish=true
fi

if [[ -n "${GITHUB_ENV:-}" ]]; then
  printf 'NETWORK_GITHUB_PUBLISH_TASKS=%s\n' "$github_missing_text" >> "$GITHUB_ENV"
  printf 'NETWORK_RAFT_PUBLISH_TASKS=%s\n' "$raft_missing_text" >> "$GITHUB_ENV"
  printf 'NETWORK_SKIP_PUBLISH=%s\n' "$skip_publish" >> "$GITHUB_ENV"
fi
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  printf 'skipped=%s\n' "$skip_publish" >> "$GITHUB_OUTPUT"
fi

echo "Immutable plan: github_missing=${#github_missing[@]} raft_missing=${#raft_missing[@]} skipped=$skip_publish."
