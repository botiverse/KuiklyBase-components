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
AUTHORITY_SOURCE_EXACT=8ffc865419ef2e210e2d78f18aedcae00ea9b9cc \
READBACK_SOURCE_EXACT=deadbeefdeadbeefdeadbeefdeadbeefdeadbeef \
READBACK_SOURCE_REF=refs/heads/master \
READBACK_RUN_ID=424242 \
READBACK_RUN_ATTEMPT=1 \
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
    AUTHORITY_SOURCE_EXACT=8ffc865419ef2e210e2d78f18aedcae00ea9b9cc \
    READBACK_SOURCE_EXACT=deadbeefdeadbeefdeadbeefdeadbeefdeadbeef \
    READBACK_SOURCE_REF=refs/heads/master \
    READBACK_RUN_ID=424242 \
    READBACK_RUN_ATTEMPT=1 \
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

# The frozen authority set: an unrelated or path-capable version must be
# refused at admission, before it can reach the authenticated transport or be
# interpolated into a destination path.
run_case "unrelated version refused at admission" \
  "is not the frozen authority version" MAVEN_VERSION=9.9.9-raft.0
run_case "traversal version refused at admission" \
  "is not the frozen authority version" MAVEN_VERSION="../../../../tmp/pwned"
run_case "ohos suffix is derived, not dispatchable" \
  "is not the frozen authority version" MAVEN_VERSION=0.1.0-raft.0-ohos

# Provenance must be present and correct before any transfer, so a detached
# bundle can prove which two trees produced it.
run_case "missing manifest provenance fails closed" \
  "AUTHORITY_SOURCE_EXACT" AUTHORITY_SOURCE_EXACT=
run_case "missing readback provenance fails closed" \
  "READBACK_SOURCE_EXACT" READBACK_SOURCE_EXACT=
run_case "wrong manifest exact refused" \
  "is not the publication exact" \
  AUTHORITY_SOURCE_EXACT=1111111111111111111111111111111111111111
run_case "missing ref provenance fails closed" \
  "READBACK_SOURCE_REF" READBACK_SOURCE_REF=
# Run identity is part of the carrier's proof, so an empty run id or attempt
# must fail before transfer rather than produce an unattributable bundle.
run_case "missing run id fails closed" \
  "READBACK_RUN_ID" READBACK_RUN_ID=
run_case "missing run attempt fails closed" \
  "READBACK_RUN_ATTEMPT" READBACK_RUN_ATTEMPT=

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

# The pre-signed Location carries the signature: it must never reach a log,
# an error message or the receipt, on either the failing or the passing path.
SECRET_SIG="s3cr3t-signature-must-not-leak"
mock_reset 302 200; MOCK_LOCS=("https://evil.example/o?sig=$SECRET_SIG" "")
leak_out="$( ( fetch datetime 0.1.0-raft.0 authority.txt ) 2>&1 || true )"
if printf '%s' "$leak_out" | grep -qF "$SECRET_SIG"; then
  no "refused redirect must not echo the signed Location"
else
  ok "refused redirect does not echo the signed Location"
fi

mock_reset 302 200
MOCK_LOCS=("https://github-registry-files.githubusercontent.com/o?sig=$SECRET_SIG" "")
downloaded=0
pass_out="$( ( fetch datetime 0.1.0-raft.0 authority.txt ) 2>&1 || true )"
if printf '%s' "$pass_out" | grep -qF "$SECRET_SIG"; then
  no "successful fetch must not echo the signed Location"
else
  ok "successful fetch does not echo the signed Location"
fi
# Non-vacuity: the signature must really have travelled through the loop,
# otherwise the two checks above would pass without proving anything.
if printf '%s' "$(hop_url 2)" | grep -qF "$SECRET_SIG"; then
  ok "signed Location did reach the transport (leak checks are live)"
else
  no "signed Location did reach the transport (leak checks are live)"
fi
if grep -rqF "$SECRET_SIG" "$source_out" 2>/dev/null; then
  no "signed Location must not reach the manifest/receipt"
else
  ok "signed Location does not reach the manifest/receipt"
fi

# A hostile manifest entry must not be able to write outside the bundle.
#
# The probe watches the destination the path actually normalizes to, not an
# arbitrary location: fetch builds
#   $BYTES_DIR/build/raft/kuiklybase/<artifact>/<version>/<file>
# so the file sits five directories below BYTES_DIR. Six "../" therefore lands
# in BYTES_DIR's parent. Watching anything else would leave this tooth green no
# matter what the guards do.
escape_rel="../../../../../../escape-probe-dir/marker.txt"
escape_outside_dir="$(dirname "$(cd "$BYTES_DIR" && pwd -P)")/escape-probe-dir"
rm -rf "$escape_outside_dir"

mock_reset 200
if ( fetch datetime 0.1.0-raft.0 "$escape_rel" ) >/dev/null 2>&1; then
  no "traversal in a manifest entry is refused"
else
  ok "traversal in a manifest entry is refused"
fi
# Two separate observations, so removing either guard is caught on its own: the
# lexical check runs before the mkdir, the containment check before the write.
if [ -d "$escape_outside_dir" ]; then
  no "no directory was created outside the bundle"
else
  ok "no directory was created outside the bundle"
fi
if [ -e "$escape_outside_dir/marker.txt" ]; then
  no "nothing was written outside the bundle directory"
else
  ok "nothing was written outside the bundle directory"
fi

mock_reset 200
if ( fetch datetime 0.1.0-raft.0 "sub/../still-inside.txt" ) >/dev/null 2>&1; then
  no "embedded .. segment is refused"
else
  ok "embedded .. segment is refused"
fi

unset -f curl
rm -rf "$fixture_root"

echo "== pre-upload receipt assertion =="
# The last gate before a bundle becomes authority. Driven here with crafted
# receipts, because an assertion that only runs on a real dispatch has never
# been tested by anything.
ASSERT="$here/assert-readback-receipt.py"
receipt_dir="$(mktemp -d)"

write_receipt() {
  # write_receipt <path> <python-dict-mutation>
  python3 - "$1" "$2" <<'PY'
import json, sys
path, mutation = sys.argv[1], sys.argv[2]
receipt = {
    "status": "complete",
    "repository": "botiverse/KuiklyBase-components",
    "version": "0.1.0-raft.0",
    "fileCount": 2,
    "provenance": {
        "manifestSourceExact": "8ffc865419ef2e210e2d78f18aedcae00ea9b9cc",
        "readbackSourceExact": "cafe1234cafe1234cafe1234cafe1234cafe1234",
        "readbackRef": "refs/heads/master",
        "runId": "424242",
        "runAttempt": "1",
    },
    "files": [
        {"path": "build/raft/kuiklybase/datetime/0.1.0-raft.0/a.pom", "sha256": "a" * 64},
        {"path": "build/raft/kuiklybase/datetime/0.1.0-raft.0/b.pom", "sha256": "b" * 64},
    ],
}
exec(mutation)
json.dump(receipt, open(path, "w"))
PY
}

assert_receipt() {
  # assert_receipt <label> <expect-pass|expect-fail> <mutation> [env...]
  local label="$1" mode="$2" mutation="$3"; shift 3
  local file="$receipt_dir/receipt.json" out rc=0
  write_receipt "$file" "$mutation"
  out="$(env EXPECT_VERSION=0.1.0-raft.0 EXPECT_COUNT=2 \
    EXPECT_MANIFEST_EXACT=8ffc865419ef2e210e2d78f18aedcae00ea9b9cc \
    EXPECT_READBACK_EXACT=cafe1234cafe1234cafe1234cafe1234cafe1234 \
    EXPECT_READBACK_REF=refs/heads/master \
    EXPECT_RUN_ID=424242 EXPECT_RUN_ATTEMPT=1 \
    "$@" python3 "$ASSERT" "$file" 2>&1)" || rc=$?
  if [ "$mode" = "expect-pass" ]; then
    if [ "$rc" -eq 0 ]; then ok "$label"; else no "$label (got: $(printf '%s' "$out" | tail -1))"; fi
  else
    if [ "$rc" -ne 0 ]; then ok "$label"; else no "$label (assertion accepted it)"; fi
  fi
}

assert_receipt "a well-formed receipt passes" expect-pass "pass"
assert_receipt "wrong run attempt is refused" expect-fail \
  "receipt['provenance']['runAttempt'] = '2'"
assert_receipt "wrong run id is refused" expect-fail \
  "receipt['provenance']['runId'] = '999'"
assert_receipt "wrong readback exact is refused" expect-fail \
  "receipt['provenance']['readbackSourceExact'] = 'f'*40"
assert_receipt "wrong manifest exact is refused" expect-fail \
  "receipt['provenance']['manifestSourceExact'] = '1'*40"
assert_receipt "wrong ref is refused" expect-fail \
  "receipt['provenance']['readbackRef'] = 'refs/heads/other'"
assert_receipt "empty run attempt is refused" expect-fail \
  "receipt['provenance']['runAttempt'] = ''"
assert_receipt "missing provenance is refused" expect-fail \
  "del receipt['provenance']"
assert_receipt "short digest is refused" expect-fail \
  "receipt['files'][0]['sha256'] = 'abc'"
assert_receipt "non-hex digest is refused" expect-fail \
  "receipt['files'][0]['sha256'] = 'z'*64"
assert_receipt "traversal path is refused" expect-fail \
  "receipt['files'][0]['path'] = '../escaped.pom'"
assert_receipt "duplicate path is refused" expect-fail \
  "receipt['files'][1]['path'] = receipt['files'][0]['path']"
assert_receipt "count mismatch is refused" expect-fail \
  "receipt['fileCount'] = 34"
assert_receipt "unrelated version is refused" expect-fail \
  "receipt['version'] = '9.9.9-raft.0'"
assert_receipt "incomplete status is refused" expect-fail \
  "receipt['status'] = 'partial'"
rm -rf "$receipt_dir"

echo "== trigger coverage =="
# A gate whose file is not in the workflow's paths filter is a gate no pull
# request will exercise: the job simply does not run, and the PR goes green
# having tested nothing. Adding assert-readback-receipt.py without wiring its
# trigger did exactly that, so the wiring is asserted here rather than
# remembered.
TEETH_WORKFLOW="$here/../../.github/workflows/datetime-authority-readback-teeth.yml"

# Counted per block, never summed: a path listed twice under pull_request and
# not at all under push also totals two, while leaving master pushes uncovered.
_trigger_counts() {
  awk -v want="$2" '
    /^  pull_request:/ { section = "pr";   inpaths = 0 }
    /^  push:/         { section = "push"; inpaths = 0 }
    /^    paths:/      { if (section != "") inpaths = 1; next }
    /^      - /        { if (inpaths && $2 == want) n[section]++; next }
    /^[^ ]/            { section = ""; inpaths = 0 }
    END { printf "%d %d", n["pr"] + 0, n["push"] + 0 }
  ' "$1"
}

# The decision is a function of its own so the teeth can exercise it directly.
# Left inline, a later "pr + push >= 2" would restore the summing bug with
# nothing to catch it.
_trigger_ok() { [ "$1" -eq 1 ] && [ "$2" -eq 1 ]; }

if [ ! -f "$TEETH_WORKFLOW" ]; then
  no "teeth workflow is present at the expected path"
else
  ok "teeth workflow is present at the expected path"
  for guarded in \
    "DatetimeKMM/scripts/authority-readback.sh" \
    "DatetimeKMM/scripts/authority-readback-teeth.sh" \
    "DatetimeKMM/scripts/assert-readback-receipt.py" \
    ".github/workflows/datetime-authority-readback.yml" \
    ".github/workflows/datetime-authority-readback-teeth.yml"
  do
    counts="$(_trigger_counts "$TEETH_WORKFLOW" "$guarded")"
    pr_count="${counts%% *}"
    push_count="${counts##* }"
    if _trigger_ok "$pr_count" "$push_count"; then
      ok "trigger covers $guarded"
    else
      no "trigger covers $guarded (pull_request=$pr_count, push=$push_count; each must be exactly 1)"
    fi
  done
fi

# Counter and decision are both checked against fixtures, so "each block" is
# proven rather than asserted.
trigger_fixture="$(mktemp -d)"
_write_trigger_fixture() {
  local file="$1" pr="$2" push="$3" i
  {
    printf 'on:\n  pull_request:\n    paths:\n'
    for (( i = 0; i < pr; i++ )); do printf '      - GUARDED\n'; done
    printf '      - other/file.txt\n'
    printf '  push:\n    branches:\n      - master\n    paths:\n'
    for (( i = 0; i < push; i++ )); do printf '      - GUARDED\n'; done
    printf '      - other/file.txt\n'
    printf 'jobs:\n  teeth:\n    runs-on: ubuntu-latest\n'
  } > "$file"
}
expect_counts() {
  local label="$1" pr="$2" push="$3" want="$4" file="$trigger_fixture/wf.yml"
  _write_trigger_fixture "$file" "$pr" "$push"
  expect_eq "$label" "$want" "$(_trigger_counts "$file" GUARDED)"
}
expect_counts "counter sees one entry in each block"       1 1 "1 1"
expect_counts "counter keeps a duplicate and a gap apart"  2 0 "2 0"
expect_counts "counter reports a pull_request-only entry"  1 0 "1 0"
expect_counts "counter reports a push-only entry"          0 1 "0 1"
expect_counts "counter reports an absent path"             0 0 "0 0"

expect_decision() {
  local label="$1" pr="$2" push="$3" want="$4"
  if _trigger_ok "$pr" "$push"; then got=accept; else got=reject; fi
  expect_eq "$label" "$want" "$got"
}
expect_decision "one in each block is accepted"                1 1 accept
expect_decision "duplicate in one block, absent in other, rejected" 2 0 reject
expect_decision "pull_request only is rejected"                1 0 reject
expect_decision "push only is rejected"                        0 1 reject
expect_decision "absent everywhere is rejected"                0 0 reject
rm -rf "$trigger_fixture"

echo "== teeth: ${pass} passed, ${fail} failed =="
[ "$fail" -eq 0 ] || exit 1
