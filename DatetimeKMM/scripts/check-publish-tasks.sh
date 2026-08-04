#!/usr/bin/env bash
#
# Verify the Gradle publish tasks for a publish mode exist in the selected build
# tree, WITHOUT publishing or probing the network. Used in PR CI to prove the
# publish pipeline's per-platform task set is real (normal Android/metadata and
# OHOS on the Linux container; iOS on macOS).
#
# Usage: check-publish-tasks.sh <android|ios|metadata|ohos-tree> [settings-file]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

MODE="${1:?usage: check-publish-tasks.sh <android|ios|metadata|ohos-tree> [settings-file]}"
SETTINGS="${2:-}"

discover_args=(--no-daemon --console=plain)
if [[ -n "$SETTINGS" ]]; then
  discover_args+=("-c" "$SETTINGS")
fi

echo "== discovering :datetime publish tasks (mode=$MODE settings=${SETTINGS:-default}) =="
tasks_output="$(./gradlew "${discover_args[@]}" :datetime:tasks --all)"

expected=()
case "$MODE" in
  android)
    expected=("publishAndroidReleasePublicationToGithubPackagesRepository")
    ;;
  ios)
    expected=(
      "publishIosX64PublicationToGithubPackagesRepository"
      "publishIosArm64PublicationToGithubPackagesRepository"
      "publishIosSimulatorArm64PublicationToGithubPackagesRepository"
    )
    ;;
  metadata)
    expected=("publishKotlinMultiplatformPublicationToGithubPackagesRepository")
    ;;
  ohos-tree)
    expected=(
      "publishOhosArm64PublicationToGithubPackagesRepository"
      "publishKotlinMultiplatformPublicationToGithubPackagesRepository"
    )
    ;;
  *)
    echo "unknown mode: $MODE" >&2
    exit 2
    ;;
esac

fail=0
for t in "${expected[@]}"; do
  if printf '%s\n' "$tasks_output" | grep -qE "^${t}( |$)"; then
    echo "  OK   task exists: $t"
  else
    echo "  FAIL task missing: $t" >&2
    fail=1
  fi
done

if [[ "$fail" -ne 0 ]]; then
  echo "PUBLISH_TASKS_FAIL ($MODE)" >&2
  exit 1
fi
echo "PUBLISH_TASKS_OK ($MODE)"
