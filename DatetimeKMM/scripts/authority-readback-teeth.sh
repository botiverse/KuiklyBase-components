#!/usr/bin/env bash
# Teeth for authority-readback.sh. Every case runs offline: the policy helpers
# are exercised directly, and the integration cases either fail before any
# transfer or use a stub publication manifest that yields no URLs at all.
#
# Each case is a RED/green tooth for a blocker found in review of the prior
# exact: fresh-output initialization, and redirect origin/hop/scheme policy.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$here/authority-readback.sh"
pass=0
fail=0

ok() { pass=$(( pass + 1 )); echo "  ok   $1"; }
no() { fail=$(( fail + 1 )); echo "  FAIL $1" >&2; }

expect_eq() {
  local label="$1" want="$2" got="$3"
  if [ "$want" = "$got" ]; then ok "$label"; else no "$label (want '$want', got '$got')"; fi
}

expect_rejected() {
  local label="$1" url="$2"
  if _origin_of "$url" >/dev/null 2>&1; then no "$label (accepted '$url')"; else ok "$label"; fi
}

stub_dir="$(mktemp -d)"
source_out="$(mktemp -d)/sourced"
trap 'rm -rf "$stub_dir"' EXIT
cat >"$stub_dir/publish-lib.sh" <<'STUB'
# Stub manifest: yields no URLs, so no request is ever issued.
publication_urls() { :; }
STUB

echo "== policy helpers =="
# Sourced with READBACK_SELFTEST=1: config and functions load, the download
# sequence does not run. Credentials here are literals, never a real token.
# shellcheck source=authority-readback.sh
READBACK_SELFTEST=1 \
AUTHORITY_SOURCE_DIR="$stub_dir" \
MAVEN_VERSION=0.1.0-raft.0 \
GITHUB_REPOSITORY=botiverse/KuiklyBase-components \
GITHUB_PACKAGES_USERNAME=teeth GITHUB_PACKAGES_TOKEN=teeth \
READBACK_OUT_DIR="$source_out" \
  . "$SCRIPT"

expect_eq "https origin parses" \
  "https://maven.pkg.github.com" "$(_origin_of https://maven.pkg.github.com/a/b)"
expect_eq "explicit :443 normalizes to default" \
  "https://maven.pkg.github.com" "$(_origin_of https://maven.pkg.github.com:443/a)"
expect_eq "non-default port is kept distinct" \
  "https://maven.pkg.github.com:8443" "$(_origin_of https://maven.pkg.github.com:8443/a)"
expect_eq "object origin parses" \
  "https://github-registry-files.githubusercontent.com" \
  "$(_origin_of https://github-registry-files.githubusercontent.com/x?sig=1)"

expect_rejected "http scheme rejected (downgrade)"        "http://maven.pkg.github.com/a"
expect_rejected "userinfo rejected (host spoof)"          "https://maven.pkg.github.com@evil.example/a"
expect_rejected "schemeless rejected"                     "maven.pkg.github.com/a"
expect_rejected "empty host rejected"                     "https:///a"
expect_rejected "non-numeric port rejected"               "https://maven.pkg.github.com:port/a"

if _origin_allowed "https://maven.pkg.github.com"; then ok "registry origin allowed"; else no "registry origin allowed"; fi
if _origin_allowed "https://github-registry-files.githubusercontent.com"; then ok "object origin allowed"; else no "object origin allowed"; fi
if _origin_allowed "https://evil.example"; then no "foreign origin must be denied"; else ok "foreign origin denied"; fi
if _origin_allowed "https://maven.pkg.github.com:8443"; then no "off-port registry must be denied"; else ok "off-port registry denied"; fi
if _origin_allowed "https://raw.githubusercontent.com"; then no "sibling github host must be denied"; else ok "sibling github host denied"; fi

expect_eq "absolute Location passes through" \
  "https://github-registry-files.githubusercontent.com/o?sig=1" \
  "$(_resolve_location https://maven.pkg.github.com/a https://github-registry-files.githubusercontent.com/o?sig=1)"
expect_eq "rooted Location resolves against current origin" \
  "https://maven.pkg.github.com/moved" \
  "$(_resolve_location https://maven.pkg.github.com/a /moved)"
if _resolve_location https://maven.pkg.github.com/a "sideways" >/dev/null 2>&1; then
  no "path-relative Location must be rejected"
else
  ok "path-relative Location rejected"
fi

echo "== integration (no transfer) =="
run_case() {
  # run_case <label> <expected substring> [env assignments...]
  local label="$1" want="$2"; shift 2
  local out rc=0
  out="$(env AUTHORITY_SOURCE_DIR="$stub_dir" \
    MAVEN_VERSION=0.1.0-raft.0 \
    GITHUB_REPOSITORY=botiverse/KuiklyBase-components \
    GITHUB_PACKAGES_USERNAME=teeth GITHUB_PACKAGES_TOKEN=teeth \
    "$@" bash "$SCRIPT" 2>&1)" || rc=$?
  if [ "$rc" -eq 0 ]; then
    no "$label (expected failure, got success)"
  elif printf '%s' "$out" | grep -qF "$want"; then
    ok "$label"
  else
    no "$label (want '$want', got: $(printf '%s' "$out" | tail -1))"
  fi
}

# Fresh-output initialization: the prior exact truncated the manifest before
# mkdir and died on a fresh runner. It must now reach the count check instead.
fresh_out="$(mktemp -d)/nested/out"
run_case "fresh output dir is created before use" \
  "expected 34 files, got 0" READBACK_OUT_DIR="$fresh_out"
if [ -d "$fresh_out" ]; then ok "fresh output dir materialized"; else no "fresh output dir materialized"; fi

# Initial origin is validated before the one credentialed request is issued.
run_case "http base rejected before transfer" \
  "not a parsable https origin" DATETIME_REPO_BASE="http://maven.pkg.github.com/x"
run_case "foreign base rejected before transfer" \
  "is not the registry origin" DATETIME_REPO_BASE="https://evil.example/x"
run_case "userinfo base rejected before transfer" \
  "not a parsable https origin" DATETIME_REPO_BASE="https://maven.pkg.github.com@evil.example/x"
run_case "off-port base rejected before transfer" \
  "is not the registry origin" DATETIME_REPO_BASE="https://maven.pkg.github.com:8443/x"
run_case "object origin may not be the initial base" \
  "is not the registry origin" \
  DATETIME_REPO_BASE="https://github-registry-files.githubusercontent.com/x"

# The manifest must come from a materialized publication exact, not silently
# from whatever happens to sit next to the script.
missing_dir="$(mktemp -d)"
out=""; rc=0
out="$(env AUTHORITY_SOURCE_DIR="$missing_dir" MAVEN_VERSION=0.1.0-raft.0 \
  GITHUB_REPOSITORY=botiverse/KuiklyBase-components \
  GITHUB_PACKAGES_USERNAME=teeth GITHUB_PACKAGES_TOKEN=teeth \
  bash "$SCRIPT" 2>&1)" || rc=$?
if [ "$rc" -ne 0 ] && printf '%s' "$out" | grep -qF "authority source lib not found"; then
  ok "missing publication exact fails closed"
else
  no "missing publication exact fails closed"
fi

echo "== redirect loop against a mocked transport =="
# The loop itself is driven here: a shell function named curl shadows the real
# binary inside this test process only, so every hop is observable and no
# request leaves the machine. Each hop records the URL it was given and whether
# credentials were attached.
fixture_root="$(mktemp -d)"
MOCK_LOG="$fixture_root/hops.tsv"
# The script captures curl's output in a command substitution, so the mock runs
# in a subshell: the hop counter has to live in a file to survive.
MOCK_STEP_FILE="$fixture_root/step"
MOCK_CODES=(); MOCK_LOCS=()

curl() {
  local args=("$@") hdr="" out="" url="" has_auth=0 i code loc
  for (( i = 0; i < ${#args[@]}; i++ )); do
    case "${args[i]}" in
      -D) hdr="${args[i+1]}" ;;
      -o) out="${args[i+1]}" ;;
      -u) has_auth=1 ;;
    esac
  done
  url="${args[${#args[@]}-1]}"
  printf '%s\t%s\n' "$url" "$has_auth" >> "$MOCK_LOG"

  local step; step="$(cat "$MOCK_STEP_FILE")"
  code="${MOCK_CODES[$step]:-200}"
  loc="${MOCK_LOCS[$step]:-}"
  printf '%s' "$(( step + 1 ))" > "$MOCK_STEP_FILE"

  : > "$hdr"
  if [ -n "$loc" ]; then printf 'HTTP/2 %s\r\nLocation: %s\r\n\r\n' "$code" "$loc" > "$hdr"; fi
  if [ "$code" = "200" ]; then printf 'authority-bytes' > "$out"; else : > "$out"; fi
  printf '%s' "$code"
}

mock_reset() { MOCK_CODES=("$@"); MOCK_LOCS=(); printf '0' > "$MOCK_STEP_FILE"; : > "$MOCK_LOG"; }
hop_url()  { awk -F'\t' -v n="$1" 'NR==n{print $1}' "$MOCK_LOG"; }
hop_auth() { awk -F'\t' -v n="$1" 'NR==n{print $2}' "$MOCK_LOG"; }

OBJ="https://github-registry-files.githubusercontent.com/object?sig=abc"

# A. registry 302 -> pre-signed object 200: credentials on hop 0 only.
mock_reset 302 200; MOCK_LOCS=("$OBJ" ""); downloaded=0
if ( fetch datetime 0.1.0-raft.0 authority.txt ) >/dev/null 2>&1; then
  expect_eq "hop 0 goes to the registry" \
    "https://maven.pkg.github.com" "$(_origin_of "$(hop_url 1)")"
  expect_eq "hop 0 carries credentials" "1" "$(hop_auth 1)"
  expect_eq "hop 1 goes to the object origin" \
    "https://github-registry-files.githubusercontent.com" "$(_origin_of "$(hop_url 2)")"
  expect_eq "hop 1 carries NO credentials" "0" "$(hop_auth 2)"
else
  no "registry->object redirect completes"
fi

# B. chain returning to the registry must still not re-attach credentials.
mock_reset 302 200
MOCK_LOCS=("https://maven.pkg.github.com/back/authority.txt" "")
if ( fetch datetime 0.1.0-raft.0 authority.txt ) >/dev/null 2>&1; then
  expect_eq "hop 1 back on registry still has NO credentials" "0" "$(hop_auth 2)"
else
  no "registry->registry redirect completes"
fi

expect_loop_fail() {
  local label="$1" want="$2" out rc=0
  out="$( ( fetch datetime 0.1.0-raft.0 authority.txt ) 2>&1 )" || rc=$?
  if [ "$rc" -eq 0 ]; then
    no "$label (expected failure, got success)"
  elif printf '%s' "$out" | grep -qF "$want"; then
    ok "$label"
  else
    no "$label (want '$want', got: $(printf '%s' "$out" | tail -1))"
  fi
}

# C-G. every hop is policed, not just the last one.
mock_reset 302 200; MOCK_LOCS=("https://evil.example/object" "")
expect_loop_fail "redirect to a foreign origin is refused" "not allowed"

mock_reset 302 200; MOCK_LOCS=("http://github-registry-files.githubusercontent.com/o" "")
expect_loop_fail "redirect downgraded to http is refused" "not a parsable https origin"

mock_reset 302 200; MOCK_LOCS=("https://maven.pkg.github.com:8443/o" "")
expect_loop_fail "redirect to an off-port registry is refused" "not allowed"

mock_reset 302 302 302 302 302 302 200
MOCK_LOCS=("$OBJ" "$OBJ" "$OBJ" "$OBJ" "$OBJ" "$OBJ" "")
expect_loop_fail "redirect chain is bounded" "more than"

mock_reset 302 200; MOCK_LOCS=("" "")
expect_loop_fail "redirect without Location is refused" "redirect without Location"

mock_reset 403
expect_loop_fail "non-200 status fails closed" "HTTP 403"

unset -f curl
rm -rf "$fixture_root"

echo "== teeth: ${pass} passed, ${fail} failed =="
[ "$fail" -eq 0 ] || exit 1
