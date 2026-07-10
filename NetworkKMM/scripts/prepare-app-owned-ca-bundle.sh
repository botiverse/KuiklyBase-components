#!/usr/bin/env bash
# Stages the exact CA bytes an app packages and later supplies to
# VBTransportCurl.configure(). The stable dated source and SHA-256 live in a
# reviewed manifest; the moving curl.se/ca/cacert.pem URL is never accepted.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NETWORK_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
MANIFEST="${NETWORK_ROOT}/ca/curl-ca-bundle.env"

if [[ $# -ne 1 ]]; then
  echo "usage: $0 OUTPUT_PEM" >&2
  exit 2
fi
if [[ ! -f "$MANIFEST" ]]; then
  echo "CA manifest not found: $MANIFEST" >&2
  exit 2
fi

# shellcheck disable=SC1090
source "$MANIFEST"
: "${NETWORKKMM_CURL_CA_VERSION:?missing CA version}"
: "${NETWORKKMM_CURL_CA_SOURCE_URL:?missing CA source URL}"
: "${NETWORKKMM_CURL_CA_SHA256:?missing CA SHA-256}"

OUTPUT="$1"
mkdir -p "$(dirname "$OUTPUT")"
TMP="${OUTPUT}.tmp.$$"
trap 'rm -f "$TMP"' EXIT

if [[ -n "${NETWORKKMM_CURL_CA_SOURCE_FILE:-}" ]]; then
  cp "$NETWORKKMM_CURL_CA_SOURCE_FILE" "$TMP"
else
  curl -fsSL "$NETWORKKMM_CURL_CA_SOURCE_URL" -o "$TMP"
fi

if command -v sha256sum >/dev/null 2>&1; then
  actual="$(sha256sum "$TMP" | awk '{print $1}')"
else
  actual="$(shasum -a 256 "$TMP" | awk '{print $1}')"
fi
if [[ "$actual" != "$NETWORKKMM_CURL_CA_SHA256" ]]; then
  echo "CA bundle SHA-256 mismatch" >&2
  echo "version:  $NETWORKKMM_CURL_CA_VERSION" >&2
  echo "source:   $NETWORKKMM_CURL_CA_SOURCE_URL" >&2
  echo "expected: $NETWORKKMM_CURL_CA_SHA256" >&2
  echo "actual:   $actual" >&2
  exit 2
fi

mv "$TMP" "$OUTPUT"
trap - EXIT
echo "staged app-owned CA version=$NETWORKKMM_CURL_CA_VERSION sha256=$actual output=$OUTPUT"
