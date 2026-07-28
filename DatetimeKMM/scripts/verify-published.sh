#!/usr/bin/env bash
#
# Post-publish authenticated readback for DatetimeKMM. Downloads the full
# published artifact matrix from GitHub Packages (with bounded eventual-
# consistency retry), then verifies:
#   1. every expected artifact is present and readable (fail closed on missing
#      / 401 / 403 / 5xx after retries);
#   2. the root normal and OHOS module coordinates (group build.raft.kuiklybase,
#      version, and normal vs -ohos variant references);
#   3. the LICENSE/NOTICE/PROVENANCE bytes in every carrying artifact match the
#      checked-in source (reuses verify-publication-legal.sh).
#
# Env: MAVEN_VERSION, GITHUB_PACKAGES_USERNAME/TOKEN (fallback GITHUB_ACTOR/
# GITHUB_TOKEN), GITHUB_REPOSITORY.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

GITHUB_PACKAGES_USERNAME="${GITHUB_PACKAGES_USERNAME:-${GITHUB_ACTOR:-}}"
GITHUB_PACKAGES_TOKEN="${GITHUB_PACKAGES_TOKEN:-${GITHUB_TOKEN:-${GH_TOKEN:-}}}"
GITHUB_REPOSITORY="${GITHUB_REPOSITORY:-bytemain/KuiklyBase-components}"
VERSION="${MAVEN_VERSION:?MAVEN_VERSION is required}"
AUTH="$GITHUB_PACKAGES_USERNAME:$GITHUB_PACKAGES_TOKEN"
# DATETIME_REPO_BASE overrides the Maven repository base (self-tests only).
REPO_BASE="${DATETIME_REPO_BASE:-https://maven.pkg.github.com/${GITHUB_REPOSITORY}/build/raft/kuiklybase}"
READBACK_BACKOFF="${DATETIME_READBACK_BACKOFF_SECONDS:-5}"
READBACK_MAX="${DATETIME_READBACK_MAX_ATTEMPTS:-6}"

if [[ -z "$GITHUB_PACKAGES_USERNAME" || -z "$GITHUB_PACKAGES_TOKEN" ]]; then
  echo "GitHub Packages credentials are required." >&2
  exit 1
fi

m2="$(mktemp -d)"
trap 'rm -rf "$m2"' EXIT

# download <artifact> <version> <file>
# Downloads one artifact file into the m2 layout with bounded retry. Fails closed
# if it cannot be read after retries.
download() {
  local artifact="$1" version="$2" file="$3"
  local url="${REPO_BASE}/${artifact}/${version}/${file}"
  local dest="${m2}/build/raft/kuiklybase/${artifact}/${version}/${file}"
  mkdir -p "$(dirname "$dest")"
  local attempt=1
  while [ "$attempt" -le "$READBACK_MAX" ]; do
    if curl -s -f -u "$AUTH" -o "$dest" "$url"; then
      echo "  downloaded ${artifact}/${version}/${file}"
      return 0
    fi
    sleep "$(( attempt * READBACK_BACKOFF ))"
    attempt=$(( attempt + 1 ))
  done
  echo "READBACK FAIL: cannot download ${artifact}/${version}/${file} after $READBACK_MAX attempts (fail closed)" >&2
  return 1
}

echo "== readback: downloading published artifact matrix (version=$VERSION) =="

# Normal tree carrying artifacts (legal gate 'normal' + 'ios' variants).
download "datetime" "$VERSION" "datetime-$VERSION.jar"
download "datetime" "$VERSION" "datetime-$VERSION-sources.jar"
download "datetime" "$VERSION" "datetime-$VERSION.module"
download "datetime" "$VERSION" "datetime-$VERSION.pom"
download "datetime-android" "$VERSION" "datetime-android-$VERSION.aar"
download "datetime-android" "$VERSION" "datetime-android-$VERSION-sources.jar"
for tgt in iosx64 iosarm64 iossimulatorarm64; do
  download "datetime-$tgt" "$VERSION" "datetime-$tgt-$VERSION.klib"
  download "datetime-$tgt" "$VERSION" "datetime-$tgt-$VERSION-sources.jar"
done

# OHOS tree carrying artifacts (legal gate 'ohos' variant).
download "datetime" "$VERSION-ohos" "datetime-$VERSION-ohos.jar"
download "datetime" "$VERSION-ohos" "datetime-$VERSION-ohos-sources.jar"
download "datetime" "$VERSION-ohos" "datetime-$VERSION-ohos.module"
download "datetime-ohosarm64" "$VERSION-ohos" "datetime-ohosarm64-$VERSION-ohos.klib"
download "datetime-ohosarm64" "$VERSION-ohos" "datetime-ohosarm64-$VERSION-ohos-sources.jar"

echo "== readback: legal byte-equality in carrying artifacts =="
bash "$SCRIPT_DIR/verify-publication-legal.sh" "$m2" normal
bash "$SCRIPT_DIR/verify-publication-legal.sh" "$m2" ios
bash "$SCRIPT_DIR/verify-publication-legal.sh" "$m2" ohos

echo "== readback: root module coordinate / variant references =="
normal_module="$m2/build/raft/kuiklybase/datetime/$VERSION/datetime-$VERSION.module"
ohos_module="$m2/build/raft/kuiklybase/datetime/$VERSION-ohos/datetime-$VERSION-ohos.module"

grep -q '"group": "build.raft.kuiklybase"' "$normal_module" || { echo "READBACK FAIL: normal root module group != build.raft.kuiklybase" >&2; exit 1; }
grep -q "\"version\": \"$VERSION\"" "$normal_module" || { echo "READBACK FAIL: normal root module version != $VERSION" >&2; exit 1; }
grep -q 'datetime-android' "$normal_module" || { echo "READBACK FAIL: normal root module missing android variant ref" >&2; exit 1; }
grep -q 'datetime-iosarm64' "$normal_module" || { echo "READBACK FAIL: normal root module missing ios variant ref" >&2; exit 1; }

grep -q '"group": "build.raft.kuiklybase"' "$ohos_module" || { echo "READBACK FAIL: ohos root module group != build.raft.kuiklybase" >&2; exit 1; }
grep -q "\"version\": \"$VERSION-ohos\"" "$ohos_module" || { echo "READBACK FAIL: ohos root module version != $VERSION-ohos" >&2; exit 1; }
grep -q 'datetime-ohosarm64' "$ohos_module" || { echo "READBACK FAIL: ohos root module missing ohosArm64 variant ref" >&2; exit 1; }

echo "READBACK_PASS (version=$VERSION)"
