#!/usr/bin/env bash
# Read-only authority readback for the already-published DatetimeKMM matrix.
#
# Purpose (Raft task #99): the historical publish run 30342129145 published all
# seven publications, but its own verification never completed, so the 34-file
# GitHub Packages authority has never been machine-verified. This script
# downloads that authority and freezes a digest manifest so the bytes can be
# reviewed before anything is mirrored elsewhere. It publishes nothing.
#
# Contract:
#   - read-only: no writes to GitHub Packages, Raft Artifacts, tags or source;
#   - the file set comes from publish-lib.sh publication_urls(), the same locked
#     per-kind manifest the publisher and PR CI already check, so this script
#     cannot drift from the publication contract;
#   - every deviation fails closed: transport failure, non-200, empty body,
#     unexpected redirect host, corrupt archive, or a file count other than the
#     expected total;
#   - redirects: GitHub Packages answers with a 302 to a pre-signed object URL
#     on a different host. curl follows it (-L) and drops credentials across
#     hosts by default; --location-trusted is forbidden because it would forward
#     the Authorization header to that host. The effective host must be either
#     the registry itself or the single allow-listed object host below. A new
#     GitHub object host must go red here and be added by source review, never
#     by a wildcard.
#   - the receipt records paths and sha256 only: no headers, no credentials and
#     no pre-signed URLs (they embed signatures).
#
# Env: MAVEN_VERSION, GITHUB_REPOSITORY, GITHUB_PACKAGES_USERNAME/TOKEN
#      (fallback GITHUB_ACTOR/GITHUB_TOKEN), optional DATETIME_REPO_BASE,
#      READBACK_OUT_DIR (default: readback-out).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=publish-lib.sh
. "$here/publish-lib.sh"

VERSION="${MAVEN_VERSION:?MAVEN_VERSION is required}"
REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
REPO_BASE="${DATETIME_REPO_BASE:-https://maven.pkg.github.com/${REPO}/build/raft/kuiklybase}"
OUT_DIR="${READBACK_OUT_DIR:-readback-out}"
BYTES_DIR="$OUT_DIR/bytes"
RECEIPT="$OUT_DIR/readback-receipt.json"

USER_NAME="${GITHUB_PACKAGES_USERNAME:-${GITHUB_ACTOR:-}}"
TOKEN="${GITHUB_PACKAGES_TOKEN:-${GITHUB_TOKEN:-}}"
[ -n "$USER_NAME" ] && [ -n "$TOKEN" ] || { echo "READBACK FAIL: missing credentials" >&2; exit 1; }
AUTH="${USER_NAME}:${TOKEN}"

# The registry redirects downloads to this object host. Exact match only.
REGISTRY_HOST="maven.pkg.github.com"
OBJECT_HOST="github-registry-files.githubusercontent.com"

EXPECTED_TOTAL="${READBACK_EXPECTED_TOTAL:-34}"
CONNECT_TIMEOUT="${CONNECT_TIMEOUT:-20}"
MAX_TIME="${MAX_TIME:-300}"
MAX_REDIRS="${MAX_REDIRS:-5}"

_host_of() { printf '%s' "${1#*://}" | cut -d/ -f1 | cut -d: -f1; }

fail() { echo "READBACK FAIL: $*" >&2; exit 1; }

downloaded=0
: > "$OUT_DIR/.manifest.tsv"

# fetch <artifact> <version> <file>
fetch() {
  local artifact="$1" version="$2" file="$3"
  local url="${REPO_BASE}/${artifact}/${version}/${file}"
  local rel="build/raft/kuiklybase/${artifact}/${version}/${file}"
  local dest="${BYTES_DIR}/${rel}"
  mkdir -p "$(dirname "$dest")"

  local response code effective eff_host curl_exit
  # No --location-trusted: credentials must not follow the cross-host redirect.
  response="$(curl -sS -L --max-redirs "$MAX_REDIRS" \
    --connect-timeout "$CONNECT_TIMEOUT" --max-time "$MAX_TIME" \
    -w '%{http_code} %{url_effective}' -u "$AUTH" -o "$dest" "$url" 2>/dev/null)" \
    && curl_exit=0 || curl_exit=$?
  [ "$curl_exit" -eq 0 ] || fail "transport error (curl exit $curl_exit) for $rel"

  code="${response%% *}"
  effective="${response#* }"
  eff_host="$(_host_of "$effective")"
  case "$eff_host" in
    "$REGISTRY_HOST"|"$OBJECT_HOST") ;;
    *) fail "redirect to unexpected host '$eff_host' for $rel (allowed: $REGISTRY_HOST, $OBJECT_HOST)" ;;
  esac
  [ "$code" = "200" ] || fail "HTTP $code for $rel"
  [ -s "$dest" ] || fail "empty body for $rel"

  case "$file" in
    *.jar|*.aar|*.klib) unzip -t "$dest" >/dev/null 2>&1 || fail "corrupt archive: $rel" ;;
    *.module|*-kotlin-tooling-metadata.json)
      python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$dest" >/dev/null 2>&1 \
        || fail "invalid JSON: $rel" ;;
    *.pom)
      python3 -c 'import xml.etree.ElementTree as ET,sys; ET.parse(sys.argv[1])' "$dest" >/dev/null 2>&1 \
        || fail "invalid XML: $rel" ;;
  esac

  local sha
  sha="$(python3 -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' "$dest")"
  printf '%s\t%s\n' "$rel" "$sha" >> "$OUT_DIR/.manifest.tsv"
  downloaded=$(( downloaded + 1 ))
  echo "  ok ${rel}"
}

fetch_publication() {
  local artifact="$1" version="$2" kind="$3" url file
  while IFS= read -r url; do
    file="${url##*/}"
    fetch "$artifact" "$version" "$file"
  done < <(publication_urls "$REPO_BASE" "$artifact" "$version" "$kind")
}

mkdir -p "$BYTES_DIR"
echo "== authority readback: ${REPO} version=${VERSION} (read-only) =="

fetch_publication "datetime" "$VERSION" "root-metadata"
fetch_publication "datetime-android" "$VERSION" "android"
for tgt in iosx64 iosarm64 iossimulatorarm64; do
  fetch_publication "datetime-$tgt" "$VERSION" "native"
done
fetch_publication "datetime" "$VERSION-ohos" "root-metadata"
fetch_publication "datetime-ohosarm64" "$VERSION-ohos" "native-ohos"

[ "$downloaded" -eq "$EXPECTED_TOTAL" ] \
  || fail "expected $EXPECTED_TOTAL files, got $downloaded (incomplete set)"

python3 - "$OUT_DIR/.manifest.tsv" "$RECEIPT" "$VERSION" "$REPO" "$downloaded" <<'PY'
import json, sys
tsv, out, version, repo, total = sys.argv[1:6]
files = []
with open(tsv) as fh:
    for line in fh:
        line = line.rstrip("\n")
        if not line:
            continue
        path, sha = line.split("\t")
        files.append({"path": path, "sha256": sha})
files.sort(key=lambda f: f["path"])
if len({f["path"] for f in files}) != len(files):
    raise SystemExit("READBACK FAIL: duplicate path in manifest")
json.dump(
    {
        "status": "complete",
        "repository": repo,
        "version": version,
        "fileCount": int(total),
        "files": files,
    },
    open(out, "w"),
    indent=2,
    sort_keys=True,
)
open(out, "a").write("\n")
PY

rm -f "$OUT_DIR/.manifest.tsv"
echo "== authority readback complete: ${downloaded}/${EXPECTED_TOTAL} files, receipt ${RECEIPT} =="
