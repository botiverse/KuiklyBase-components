#!/usr/bin/env bash
#
# Shared fail-closed publication admission helpers for DatetimeKMM publishing.
#
# GitHub Packages Maven versions are immutable, so the publish admission must
# never mistake an authentication/rate-limit/network failure or an unvalidated
# redirect for "coordinate free", and must never treat a single representative
# file as proof that a whole multi-artifact publication is complete.
#
#   classify_http_code  pure status-code classification (unit-testable)
#   probe_url           authenticated HEAD probe with bounded retry/backoff,
#                       connect/overall timeouts, and a same-host redirect
#                       policy that follows redirects to a final 2xx/404 and
#                       fails closed on cross-host redirects, auth errors,
#                       throttling, server errors, and transport failures
#   classify_manifest   probes every expected artifact of one publication and
#                       reports NONE / COMPLETE / PARTIAL
#
# The probe command is overridable via DATETIME_PROBE_CURL for self-tests.

# classify_http_code <code>
# Prints ABSENT, EXISTS, RETRY, or FAIL. Pure logic.
#   404                  -> ABSENT  (definitely not published)
#   2xx                  -> EXISTS  (published; redirects are followed by
#                                   probe_url, so a bare 3xx never legitimately
#                                   reaches here and is treated as anomalous)
#   429 / 5xx / 000 / "" -> RETRY  (transient: rate limit, server, transport)
#   anything else        -> FAIL   (3xx/401/403/other 4xx: fail closed)
classify_http_code() {
  case "$1" in
    404) echo "ABSENT" ;;
    2??) echo "EXISTS" ;;
    429|5??|000|"") echo "RETRY" ;;
    *) echo "FAIL" ;;
  esac
}

# _host_of <url>
# Prints the host[:port] authority of a URL (best-effort, POSIX-safe). Strips
# the scheme and any userinfo (curl embeds credentials into %{url_effective}).
_host_of() {
  printf '%s' "$1" | sed -E 's#^[a-zA-Z][a-zA-Z0-9+.-]*://##; s#^[^@/]*@##; s#/.*$##'
}

# probe_url <url> <user> <token>
# Prints ABSENT or EXISTS. Returns non-zero (hard fail) on FAIL or on exhausting
# the bounded retry budget for transient errors. Never returns success for an
# inconclusive result, an unvalidated/cross-host redirect, or an auth/server
# error.
probe_url() {
  local url="$1" user="$2" token="$3"
  local max_attempts="${DATETIME_PROBE_MAX_ATTEMPTS:-4}"
  local backoff_base="${DATETIME_PROBE_BACKOFF_SECONDS:-2}"
  local curl_cmd="${DATETIME_PROBE_CURL:-curl}"
  local connect_timeout="${DATETIME_PROBE_CONNECT_TIMEOUT:-10}"
  local max_time="${DATETIME_PROBE_MAX_TIME:-30}"
  local max_redirs="${DATETIME_PROBE_MAX_REDIRS:-3}"
  local want_host
  want_host="$(_host_of "$url")"
  local attempt=1 code="" effective="" curl_exit verdict
  while [ "$attempt" -le "$max_attempts" ]; do
    # Capture http_code and effective URL together; capture curl exit status
    # separately. Do NOT append a fallback code with `|| echo` — curl already
    # prints 000 on transport failure, and appending produced 000000.
    local response
    response="$($curl_cmd -s -o /dev/null -w '%{http_code} %{url_effective}' \
      -I -L --max-redirs "$max_redirs" \
      --connect-timeout "$connect_timeout" --max-time "$max_time" \
      -u "$user:$token" "$url" 2>/dev/null)"
    curl_exit=$?
    if [ "$curl_exit" -ne 0 ]; then
      # Transport failure (refused/timeout/DNS/TLS/too-many-redirects). curl may
      # have emitted 000 or a partial code; force the transport sentinel.
      code="000"
      effective=""
    else
      code="${response%% *}"
      effective="${response#* }"
      case "$code" in ''|*[!0-9]*) code="000" ;; esac
    fi
    # Redirect policy: a followed redirect must stay on the same host. A
    # cross-host effective URL (auth/login or attacker-controlled redirect)
    # fails closed rather than classifying EXISTS.
    if [ -n "$effective" ] && [ "$effective" != "$url" ]; then
      local eff_host
      eff_host="$(_host_of "$effective")"
      if [ "$eff_host" != "$want_host" ]; then
        echo "probe FAIL: cross-host redirect to $effective for $url (fail closed)" >&2
        return 1
      fi
    fi
    verdict="$(classify_http_code "$code")"
    case "$verdict" in
      ABSENT|EXISTS)
        echo "$verdict"
        return 0
        ;;
      FAIL)
        echo "probe FAIL: HTTP $code for $url (fail closed)" >&2
        return 1
        ;;
      RETRY)
        if [ "$attempt" -lt "$max_attempts" ]; then
          sleep "$(( attempt * backoff_base ))"
        fi
        ;;
    esac
    attempt=$(( attempt + 1 ))
  done
  echo "probe FAIL: exhausted $max_attempts attempts (last HTTP ${code:-000}) for $url (fail closed)" >&2
  return 1
}

# publication_urls <base> <artifact> <version> <kind>
# Prints the full expected immutable artifact URL set for one Maven publication.
# The per-kind file lists are locked against each platform's isolated local
# publication output (verified by scripts/check-manifest-inventory.sh in PR CI);
# repository-level mutable files (maven-metadata.xml, checksums) are intentionally
# outside the per-version contract.
#   root-metadata : root KMP module (normal + OHOS): jar + pom + module +
#                   sources + kotlin-tooling-metadata.json
#   android       : aar + pom + module + sources
#   native        : iOS native target: klib + metadata.jar + pom + module + sources
#   native-ohos   : OHOS arm64: klib + pom + module + sources + cinterop klib
publication_urls() {
  local base_root="$1" artifact="$2" version="$3" kind="$4"
  local base="${base_root}/${artifact}/${version}"
  local p="${artifact}-${version}"
  case "$kind" in
    root-metadata)
      echo "$base/$p.jar"
      echo "$base/$p.pom"
      echo "$base/$p.module"
      echo "$base/$p-sources.jar"
      echo "$base/$p-kotlin-tooling-metadata.json"
      ;;
    android)
      echo "$base/$p.aar"
      echo "$base/$p.pom"
      echo "$base/$p.module"
      echo "$base/$p-sources.jar"
      ;;
    native)
      echo "$base/$p.klib"
      echo "$base/$p-metadata.jar"
      echo "$base/$p.pom"
      echo "$base/$p.module"
      echo "$base/$p-sources.jar"
      ;;
    native-ohos)
      echo "$base/$p.klib"
      echo "$base/$p.pom"
      echo "$base/$p.module"
      echo "$base/$p-sources.jar"
      echo "$base/$p-cinterop-timeService.klib"
      ;;
    *)
      echo "publication_urls: unknown kind: $kind" >&2
      return 2
      ;;
  esac
}

# classify_manifest <user> <token> <url> [<url> ...]
# Probes every expected artifact URL of one publication. Prints:
#   NONE      all artifacts ABSENT  -> caller should publish
#   COMPLETE  all artifacts EXISTS  -> caller should skip
#   PARTIAL   mixed                 -> caller must fail and bump the version
# Returns non-zero if any probe fails closed (inconclusive is not PARTIAL).
classify_manifest() {
  local user="$1" token="$2"; shift 2
  local absent=0 exists=0 url verdict
  if [ "$#" -eq 0 ]; then
    echo "classify_manifest: no artifact URLs supplied" >&2
    return 2
  fi
  for url in "$@"; do
    verdict="$(probe_url "$url" "$user" "$token")" || return 1
    case "$verdict" in
      ABSENT) absent=$(( absent + 1 )) ;;
      EXISTS) exists=$(( exists + 1 )) ;;
      *) echo "classify_manifest: unexpected verdict $verdict for $url" >&2; return 1 ;;
    esac
  done
  if [ "$exists" -eq 0 ]; then
    echo "NONE"
  elif [ "$absent" -eq 0 ]; then
    echo "COMPLETE"
  else
    echo "PARTIAL"
  fi
}
