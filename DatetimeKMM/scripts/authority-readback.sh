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
#   - the file set comes from publication_urls() read out of the *publication's
#     own source exact*, materialized separately by the caller and pointed at by
#     AUTHORITY_SOURCE_DIR. The runnable script therefore lives at the successor
#     exact while the manifest still cannot drift from what was published;
#   - every deviation fails closed: transport failure, non-200, empty body,
#     disallowed origin on any hop, corrupt archive, or a file count other than
#     the expected total;
#   - redirects are followed by a bounded MANUAL loop, never by curl -L. Each
#     Location is resolved to an absolute URL and its full origin (scheme, host
#     and effective port) is validated against the two exact allowed origins
#     before the next request is issued. A new GitHub object host must go red
#     here and be added by source review, never by a wildcard;
#   - credentials are attached to the FIRST request only, which is proven to be
#     the registry origin before any transfer happens. No later hop re-attaches
#     them, even if the chain returns to the registry origin. Pre-signed object
#     URLs need no credentials, so none are offered to them;
#   - HTTPS only: --proto/--proto-redir pin the scheme and the origin check
#     rejects any non-https URL, so an http downgrade cannot carry a request;
#   - the receipt records paths and sha256 only: no headers, no credentials and
#     no pre-signed URLs or Location values (they embed signatures).
#
# Env: MAVEN_VERSION, GITHUB_REPOSITORY, GITHUB_PACKAGES_USERNAME/TOKEN
#      (fallback GITHUB_ACTOR/GITHUB_TOKEN), AUTHORITY_SOURCE_DIR (defaults to
#      this script's directory), optional DATETIME_REPO_BASE, READBACK_OUT_DIR
#      (default: readback-out).
set -euo pipefail

fail() { echo "READBACK FAIL: $*" >&2; exit 1; }

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# The publication manifest is read from the publication's own source exact,
# materialized by the caller. Falling back to $here keeps local use working.
AUTHORITY_SOURCE_DIR="${AUTHORITY_SOURCE_DIR:-$here}"
AUTHORITY_LIB="$AUTHORITY_SOURCE_DIR/publish-lib.sh"
[ -f "$AUTHORITY_LIB" ] \
  || fail "authority source lib not found: $AUTHORITY_LIB (materialize the publication exact first)"
# shellcheck source=publish-lib.sh
. "$AUTHORITY_LIB"
command -v publication_urls >/dev/null 2>&1 \
  || fail "publication_urls() not provided by $AUTHORITY_LIB"

# Exact allowed origins: scheme + host + effective port. Nothing else.
REGISTRY_ORIGIN="https://maven.pkg.github.com"
OBJECT_ORIGIN="https://github-registry-files.githubusercontent.com"

EXPECTED_TOTAL="${READBACK_EXPECTED_TOTAL:-34}"
CONNECT_TIMEOUT="${CONNECT_TIMEOUT:-20}"
MAX_TIME="${MAX_TIME:-300}"
MAX_REDIRS="${MAX_REDIRS:-5}"

# Normalize a URL to scheme://host[:port], dropping the default 443. Returns
# non-zero for anything unparsable, non-https, or carrying userinfo (which
# would let "https://allowed.host@evil.example" read as the allowed host).
_origin_of() {
  local url="$1" scheme rest hostport host port
  case "$url" in
    *://*) ;;
    *) return 1 ;;
  esac
  scheme="${url%%://*}"
  [ "$scheme" = "https" ] || return 1
  rest="${url#*://}"
  hostport="${rest%%/*}"
  case "$hostport" in
    ''|*@*) return 1 ;;
  esac
  host="${hostport%%:*}"
  [ -n "$host" ] || return 1
  if [ "$hostport" = "$host" ]; then
    port=""
  else
    port="${hostport#*:}"
    case "$port" in
      ''|*[!0-9]*) return 1 ;;
      443) port="" ;;
    esac
  fi
  if [ -n "$port" ]; then
    printf '%s://%s:%s' "$scheme" "$host" "$port"
  else
    printf '%s://%s' "$scheme" "$host"
  fi
}

# An origin is allowed only if it matches one of the two exact origins.
_origin_allowed() {
  case "$1" in
    "$REGISTRY_ORIGIN"|"$OBJECT_ORIGIN") return 0 ;;
    *) return 1 ;;
  esac
}

# Last Location header, CR stripped. Never logged: it carries the signature.
_last_location() {
  tr -d '\r' < "$1" \
    | awk 'BEGIN{IGNORECASE=1} /^location:/{sub(/^[Ll]ocation:[ \t]*/,""); v=$0} END{if (v) print v}'
}

# Resolve a possibly-relative Location against the current absolute URL.
_resolve_location() {
  local base="$1" loc="$2" base_origin
  case "$loc" in
    *://*) printf '%s' "$loc"; return 0 ;;
    /*)
      base_origin="$(_origin_of "$base")" || return 1
      printf '%s%s' "$base_origin" "$loc"
      return 0
      ;;
    *) return 1 ;;
  esac
}

VERSION="${MAVEN_VERSION:?MAVEN_VERSION is required}"
REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
REPO_BASE="${DATETIME_REPO_BASE:-https://maven.pkg.github.com/${REPO}/build/raft/kuiklybase}"
OUT_DIR="${READBACK_OUT_DIR:-readback-out}"
BYTES_DIR="$OUT_DIR/bytes"
RECEIPT="$OUT_DIR/readback-receipt.json"

USER_NAME="${GITHUB_PACKAGES_USERNAME:-${GITHUB_ACTOR:-}}"
TOKEN="${GITHUB_PACKAGES_TOKEN:-${GITHUB_TOKEN:-}}"
[ -n "$USER_NAME" ] && [ -n "$TOKEN" ] || fail "missing credentials"
AUTH="${USER_NAME}:${TOKEN}"

# Validate the initial origin BEFORE any credentialed transfer: the documented
# DATETIME_REPO_BASE override must not be able to hand the token to a foreign
# origin, and the first request is the only one that carries credentials.
BASE_ORIGIN="$(_origin_of "$REPO_BASE")" \
  || fail "initial repo base is not a parsable https origin"
[ "$BASE_ORIGIN" = "$REGISTRY_ORIGIN" ] \
  || fail "initial origin $BASE_ORIGIN is not the registry origin $REGISTRY_ORIGIN"

# Output roots must exist before anything is written into them.
mkdir -p "$OUT_DIR" "$BYTES_DIR"
MANIFEST_TSV="$OUT_DIR/.manifest.tsv"
downloaded=0
: > "$MANIFEST_TSV"

# fetch <artifact> <version> <file>
fetch() {
  local artifact="$1" version="$2" file="$3"
  local url="${REPO_BASE}/${artifact}/${version}/${file}"
  local rel="build/raft/kuiklybase/${artifact}/${version}/${file}"
  local dest="${BYTES_DIR}/${rel}"
  mkdir -p "$(dirname "$dest")"

  local hdr hops=0 code origin loc next
  hdr="$(mktemp)"
  # shellcheck disable=SC2064
  trap "rm -f '$hdr'" RETURN

  while :; do
    origin="$(_origin_of "$url")" \
      || fail "hop $hops for $rel is not a parsable https origin"
    _origin_allowed "$origin" \
      || fail "hop $hops origin $origin not allowed for $rel (allowed: $REGISTRY_ORIGIN, $OBJECT_ORIGIN)"

    # Credentials ride the first request only. It is proven above to be the
    # registry origin; no later hop re-attaches them, even back on registry.
    if [ "$hops" -eq 0 ]; then
      code="$(curl -sS --proto '=https' --proto-redir '=https' \
        --connect-timeout "$CONNECT_TIMEOUT" --max-time "$MAX_TIME" \
        -D "$hdr" -o "$dest" -w '%{http_code}' -u "$AUTH" "$url" 2>/dev/null)" \
        || fail "transport error on hop $hops for $rel"
    else
      code="$(curl -sS --proto '=https' --proto-redir '=https' \
        --connect-timeout "$CONNECT_TIMEOUT" --max-time "$MAX_TIME" \
        -D "$hdr" -o "$dest" -w '%{http_code}' "$url" 2>/dev/null)" \
        || fail "transport error on hop $hops for $rel"
    fi

    case "$code" in
      200) break ;;
      301|302|303|307|308)
        hops=$(( hops + 1 ))
        [ "$hops" -le "$MAX_REDIRS" ] || fail "more than $MAX_REDIRS redirects for $rel"
        loc="$(_last_location "$hdr")"
        [ -n "$loc" ] || fail "redirect without Location on hop $hops for $rel"
        next="$(_resolve_location "$url" "$loc")" \
          || fail "unresolvable redirect target on hop $hops for $rel"
        url="$next"
        ;;
      *) fail "HTTP $code on hop $hops for $rel" ;;
    esac
  done

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
  printf '%s\t%s\n' "$rel" "$sha" >> "$MANIFEST_TSV"
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

if [ "${READBACK_SELFTEST:-}" = "1" ]; then
  # Teeth source this file to drive the policy helpers and the redirect loop
  # itself against a mocked transport. Nothing below this line runs, so no
  # request is ever issued by a sourcing test.
  return 0 2>/dev/null || exit 0
fi

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

python3 - "$MANIFEST_TSV" "$RECEIPT" "$VERSION" "$REPO" "$downloaded" <<'PY'
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

rm -f "$MANIFEST_TSV"
echo "== authority readback complete: ${downloaded}/${EXPECTED_TOTAL} files, receipt ${RECEIPT} =="
