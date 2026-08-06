#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/network-curl-compat.sh"

test_dir="$(mktemp -d "${TMPDIR:-/tmp}/network-curl-compat.XXXXXX")"
cleanup() {
  rm -rf "$test_dir"
}
trap cleanup EXIT

fake_bin="$test_dir/bin"
fake_log="$test_dir/curl-arguments.log"
expected_log="$test_dir/expected-arguments.log"
mkdir -p "$fake_bin"

cat > "$fake_bin/curl" <<'FAKE_CURL'
#!/usr/bin/env bash
set -euo pipefail

: "${NETWORK_FAKE_CURL_MODE:?}"
: "${NETWORK_FAKE_CURL_LOG:?}"

saw_version=false
saw_retry_all_errors=false
for arg in "$@"; do
  if [[ "$arg" == "--version" ]]; then
    saw_version=true
  elif [[ "$arg" == "--retry-all-errors" ]]; then
    saw_retry_all_errors=true
  fi
done

if [[ "$saw_version" == "true" ]]; then
  if [[ "$NETWORK_FAKE_CURL_MODE" == "old" && "$saw_retry_all_errors" == "true" ]]; then
    echo "curl: option --retry-all-errors: is unknown" >&2
    exit 2
  fi
  echo "curl 8.0.0 fake"
  exit 0
fi

printf '%s\n' "$@" >> "$NETWORK_FAKE_CURL_LOG"
printf '200'
FAKE_CURL
chmod +x "$fake_bin/curl"

export PATH="$fake_bin:$PATH"
export NETWORK_FAKE_CURL_LOG="$fake_log"

run_case() {
  local mode="$1"
  local expect_retry_all_errors="$2"
  local response
  local -a expected_args=(
    --silent
    --show-error
    --connect-timeout
    15
    --max-time
    60
    --retry
    2
  )

  export NETWORK_FAKE_CURL_MODE="$mode"
  : > "$fake_log"
  network_resolve_curl_retry_args
  if [[ "$expect_retry_all_errors" == "true" ]]; then
    expected_args+=(--retry-all-errors)
  fi
  expected_args+=(
    --head
    --output
    "$test_dir/output file"
    --write-out
    '%{http_code}'
    --netrc-file
    "$test_dir/auth file"
    'https://example.invalid/com/tencent/network.pom?contract=1'
  )

  response="$(network_curl \
    --head \
    --output "$test_dir/output file" \
    --write-out '%{http_code}' \
    --netrc-file "$test_dir/auth file" \
    'https://example.invalid/com/tencent/network.pom?contract=1')"
  if [[ "$response" != "200" ]]; then
    echo "$mode curl compatibility case returned $response instead of HTTP 200." >&2
    exit 1
  fi

  printf '%s\n' "${expected_args[@]}" > "$expected_log"
  if ! cmp -s "$expected_log" "$fake_log"; then
    echo "$mode curl compatibility case changed the retry, timeout, auth, or caller argument contract." >&2
    diff -u "$expected_log" "$fake_log" >&2 || true
    exit 1
  fi
}

export NETWORK_FAKE_CURL_MODE=old
if command curl --retry-all-errors --version >/dev/null 2>&1; then
  echo "The fake old curl must reject the formerly unconditional option." >&2
  exit 1
fi

run_case old false
run_case modern true

echo "old/modern curl compatibility and exact argument forwarding: PASS"
