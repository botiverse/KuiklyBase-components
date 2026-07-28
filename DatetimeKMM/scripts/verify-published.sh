#!/usr/bin/env bash
#
# Post-publish authenticated full-matrix readback for DatetimeKMM.
#
# Downloads the complete expected artifact matrix from GitHub Packages (every
# publication's full immutable carrier set, derived from publication_urls), with
# bounded eventual-consistency retry and a same-host validated redirect policy,
# then verifies:
#   1. every expected artifact is present, non-empty, and archive/JSON/XML
#      integrity-valid (fail closed on missing/auth/redirect/empty/corrupt);
#   2. the normal root module coordinates (group build.raft.kuiklybase, version)
#      and variant references for android + all three iOS targets, and the OHOS
#      root module coordinates + ohosArm64 reference, plus the negative
#      normal-vs-OHOS cross-tree boundary;
#   3. the LICENSE/NOTICE/PROVENANCE bytes in every carrying artifact match the
#      checked-in source (reuses verify-publication-legal.sh).
#
# Env: MAVEN_VERSION, GITHUB_PACKAGES_USERNAME/TOKEN (fallback GITHUB_ACTOR/
# GITHUB_TOKEN), GITHUB_REPOSITORY. DATETIME_REPO_BASE overrides the base
# (self-tests only).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=publish-lib.sh
source "$SCRIPT_DIR/publish-lib.sh"
cd "$PROJECT_DIR"

GITHUB_PACKAGES_USERNAME="${GITHUB_PACKAGES_USERNAME:-${GITHUB_ACTOR:-}}"
GITHUB_PACKAGES_TOKEN="${GITHUB_PACKAGES_TOKEN:-${GITHUB_TOKEN:-${GH_TOKEN:-}}}"
GITHUB_REPOSITORY="${GITHUB_REPOSITORY:-bytemain/KuiklyBase-components}"
VERSION="${MAVEN_VERSION:?MAVEN_VERSION is required}"
REPO_BASE="${DATETIME_REPO_BASE:-https://maven.pkg.github.com/${GITHUB_REPOSITORY}/build/raft/kuiklybase}"
AUTH="$GITHUB_PACKAGES_USERNAME:$GITHUB_PACKAGES_TOKEN"
READBACK_BACKOFF="${DATETIME_READBACK_BACKOFF_SECONDS:-5}"
READBACK_MAX="${DATETIME_READBACK_MAX_ATTEMPTS:-6}"
CONNECT_TIMEOUT="${DATETIME_PROBE_CONNECT_TIMEOUT:-10}"
MAX_TIME="${DATETIME_PROBE_MAX_TIME:-60}"
MAX_REDIRS="${DATETIME_PROBE_MAX_REDIRS:-3}"

if [[ -z "$GITHUB_PACKAGES_USERNAME" || -z "$GITHUB_PACKAGES_TOKEN" ]]; then
  echo "GitHub Packages credentials are required." >&2
  exit 1
fi

m2="$(mktemp -d)"
trap 'rm -rf "$m2"' EXIT

# download <artifact> <version> <file>
# Downloads one artifact into the m2 layout with bounded retry, a same-host
# validated redirect policy, and a non-empty requirement. Fails closed.
download() {
  local artifact="$1" version="$2" file="$3"
  local url="${REPO_BASE}/${artifact}/${version}/${file}"
  local dest="${m2}/build/raft/kuiklybase/${artifact}/${version}/${file}"
  mkdir -p "$(dirname "$dest")"
  local want_host eff_host code effective response curl_exit attempt=1
  want_host="$(_host_of "$url")"
  while [ "$attempt" -le "$READBACK_MAX" ]; do
    # Capture curl status in a conditional context so `set -e` does not abort at
    # the assignment on a transport failure (refused/DNS/TLS/timeout); the
    # bounded retry loop below must run instead of exiting immediately.
    response="$(curl -s -L --max-redirs "$MAX_REDIRS" \
      --connect-timeout "$CONNECT_TIMEOUT" --max-time "$MAX_TIME" \
      -w '%{http_code} %{url_effective}' -u "$AUTH" -o "$dest" "$url" 2>/dev/null)" \
      && curl_exit=0 || curl_exit=$?
    if [ "$curl_exit" -eq 0 ]; then
      code="${response%% *}"; effective="${response#* }"
      eff_host="$(_host_of "$effective")"
      if [ "$effective" != "$url" ] && [ "$eff_host" != "$want_host" ]; then
        echo "READBACK FAIL: cross-host redirect to $effective for $file" >&2
        return 1
      fi
      if [ "$code" = "200" ] && [ -s "$dest" ]; then
        echo "  downloaded ${artifact}/${version}/${file}"
        return 0
      fi
      # 404/401/403/5xx/empty: 404 means not published yet (retry for eventual
      # consistency); auth/server errors fail closed immediately.
      case "$code" in
        401|403)
          echo "READBACK FAIL: HTTP $code (auth) for ${artifact}/${version}/${file}" >&2
          return 1 ;;
      esac
    fi
    sleep "$(( attempt * READBACK_BACKOFF ))"
    attempt=$(( attempt + 1 ))
  done
  echo "READBACK FAIL: cannot download ${artifact}/${version}/${file} after $READBACK_MAX attempts (fail closed)" >&2
  return 1
}

# check_archive <path>: assert a JAR/AAR/KLIB is a non-corrupt zip archive.
check_archive() {
  local path="$1"
  if ! unzip -t "$path" >/dev/null 2>&1; then
    echo "READBACK FAIL: corrupt or non-zip archive: $path" >&2
    return 1
  fi
}

# check_json <path> / check_xml <path>: assert well-formed.
check_json() { python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$1" || { echo "READBACK FAIL: invalid JSON: $1" >&2; return 1; }; }
check_xml()  { python3 -c 'import xml.etree.ElementTree as ET,sys; ET.parse(sys.argv[1])' "$1" || { echo "READBACK FAIL: invalid XML: $1" >&2; return 1; }; }

# download_publication <artifact> <version> <kind>
# Downloads every file in the publication's locked manifest and integrity-checks
# each by type.
download_publication() {
  local artifact="$1" version="$2" kind="$3"
  local url file
  while IFS= read -r url; do
    file="${url##*/}"
    download "$artifact" "$version" "$file"
    local path="${m2}/build/raft/kuiklybase/${artifact}/${version}/${file}"
    case "$file" in
      *.jar|*.aar|*.klib) check_archive "$path" ;;
      *.module|*-kotlin-tooling-metadata.json) check_json "$path" ;;
      *.pom) check_xml "$path" ;;
    esac
  done < <(publication_urls "$REPO_BASE" "$artifact" "$version" "$kind")
}

echo "== readback: downloading + integrity-checking full matrix (version=$VERSION) =="
download_publication "datetime" "$VERSION" "root-metadata"
download_publication "datetime-android" "$VERSION" "android"
for tgt in iosx64 iosarm64 iossimulatorarm64; do
  download_publication "datetime-$tgt" "$VERSION" "native"
done
download_publication "datetime" "$VERSION-ohos" "root-metadata"
download_publication "datetime-ohosarm64" "$VERSION-ohos" "native-ohos"

echo "== readback: legal byte-equality in carrying artifacts =="
# Pin the version explicitly: the readback stages both <v> and <v>-ohos under
# datetime/, so discovery (ls|head -1) would select the normal root for the OHOS
# call and false-green the OHOS root legal contract.
bash "$SCRIPT_DIR/verify-publication-legal.sh" "$m2" normal "$VERSION"
bash "$SCRIPT_DIR/verify-publication-legal.sh" "$m2" ios "$VERSION"
bash "$SCRIPT_DIR/verify-publication-legal.sh" "$m2" ohos "$VERSION-ohos"

echo "== readback: exact coordinate / variant-reference validation =="
# Parses every downloaded module/POM for exact group/artifact/version and the
# root modules' structured variants[*].available-at for the exact expected
# target set plus the negative normal-vs-OHOS boundary (no grep).
python3 "$SCRIPT_DIR/verify-coordinates.py" "$m2" "$VERSION"

echo "READBACK_PASS (version=$VERSION)"
