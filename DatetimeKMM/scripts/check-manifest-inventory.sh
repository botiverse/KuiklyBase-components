#!/usr/bin/env bash
#
# Lock the expected-artifact manifest against a platform's real isolated local
# publication output. Publishes the selected build tree to an isolated
# mavenLocal, then for every publication in the mode compares the actual
# immutable files on disk (excluding repository-level mutable maven-metadata.xml
# and .sha1/.md5 checksums) against publication_urls. Any drift fails, so the
# admission/readback manifest cannot silently omit a real published file.
#
# Usage: check-manifest-inventory.sh <android|ios|metadata|ohos-tree> [settings-file]
# Requires a build environment for the selected tree (Android SDK; OHOS SDK for
# ohos-tree; macOS for ios).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=publish-lib.sh
source "$SCRIPT_DIR/publish-lib.sh"
cd "$PROJECT_DIR"

MODE="${1:?usage: check-manifest-inventory.sh <android|ios|metadata|ohos-tree> [settings-file]}"
SETTINGS="${2:-}"
VERSION="${MAVEN_VERSION:-$(grep '^mavenVersion=' gradle.properties | cut -d= -f2)}"

m2="$(mktemp -d)"
trap 'rm -rf "$m2"' EXIT

publish_args=(--no-daemon --console=plain -PmavenVersion="$VERSION")
if [[ -n "$SETTINGS" ]]; then publish_args+=("-c" "$SETTINGS"); fi

echo "== publishing isolated local publication (mode=$MODE version=$VERSION) =="
case "$MODE" in
  android)
    ./gradlew "${publish_args[@]}" :datetime:publishAndroidReleasePublicationToMavenLocal \
      :datetime:publishKotlinMultiplatformPublicationToMavenLocal -Dmaven.repo.local="$m2" >/dev/null
    specs="datetime-android/$VERSION/android datetime/$VERSION/root-metadata"
    ;;
  metadata)
    ./gradlew "${publish_args[@]}" :datetime:publishKotlinMultiplatformPublicationToMavenLocal \
      -Dmaven.repo.local="$m2" >/dev/null
    specs="datetime/$VERSION/root-metadata"
    ;;
  ios)
    ./gradlew "${publish_args[@]}" \
      :datetime:publishIosX64PublicationToMavenLocal \
      :datetime:publishIosArm64PublicationToMavenLocal \
      :datetime:publishIosSimulatorArm64PublicationToMavenLocal \
      -Dmaven.repo.local="$m2" >/dev/null
    specs="datetime-iosx64/$VERSION/native datetime-iosarm64/$VERSION/native datetime-iossimulatorarm64/$VERSION/native"
    ;;
  ohos-tree)
    ./gradlew "${publish_args[@]}" :datetime:publishOhosArm64PublicationToMavenLocal \
      :datetime:publishKotlinMultiplatformPublicationToMavenLocal -Dmaven.repo.local="$m2" >/dev/null
    specs="datetime-ohosarm64/$VERSION-ohos/native-ohos datetime/$VERSION-ohos/root-metadata"
    ;;
  *)
    echo "unknown mode: $MODE" >&2; exit 2 ;;
esac

fail=0
for spec in $specs; do
  artifact="${spec%%/*}"
  rest="${spec#*/}"
  version="${rest%%/*}"
  kind="${rest##*/}"
  dir="$m2/build/raft/kuiklybase/$artifact/$version"
  if [[ ! -d "$dir" ]]; then
    echo "  FAIL publication dir missing: $dir" >&2
    fail=1
    continue
  fi
  # Actual immutable files (drop maven-metadata.xml and checksum sidecars).
  actual="$(cd "$dir" && ls -1 | grep -vE '^maven-metadata\.xml$|\.(sha1|md5|sha256|sha512)$' | sort)"
  # Expected filenames from the locked manifest (basenames of the URLs).
  expected="$(publication_urls "https://x" "$artifact" "$version" "$kind" | sed -E 's#.*/##' | sort)"
  if [[ "$actual" == "$expected" ]]; then
    echo "  OK   $artifact:$version manifest matches isolated publication ($(printf '%s\n' "$actual" | grep -c .) files)"
  else
    echo "  FAIL $artifact:$version manifest drift" >&2
    echo "    expected:" >&2; printf '%s\n' "$expected" | sed 's/^/      /' >&2
    echo "    actual:" >&2;   printf '%s\n' "$actual"   | sed 's/^/      /' >&2
    fail=1
  fi
done

if [[ "$fail" -ne 0 ]]; then
  echo "MANIFEST_INVENTORY_FAIL ($MODE)" >&2
  exit 1
fi
echo "MANIFEST_INVENTORY_PASS ($MODE)"
