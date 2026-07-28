#!/usr/bin/env bash
#
# Shared fail-closed publication admission helpers for DatetimeKMM publishing.
#
# GitHub Packages Maven versions are immutable, so the publish admission must
# never mistake an authentication/rate-limit/network failure for "coordinate
# free", and must never treat a single representative file as proof that a whole
# multi-artifact publication is complete. These helpers provide:
#
#   classify_http_code  pure status-code classification (unit-testable)
#   probe_url           authenticated HEAD probe with bounded retry/backoff that
#                       fails closed on anything other than a definite 404
#                       (ABSENT) or 2xx/3xx (EXISTS)
#   classify_manifest   probes every expected artifact of one publication and
#                       reports NONE / COMPLETE / PARTIAL
#
# The probe command is overridable via DATETIME_PROBE_CURL for self-tests.

# classify_http_code <code>
# Prints ABSENT, EXISTS, RETRY, or FAIL. Pure logic.
#   404                 -> ABSENT  (definitely not published)
#   2xx / 3xx           -> EXISTS  (published; redirects allowed)
#   429 / 5xx / 000 / ""-> RETRY  (transient: rate limit, server, transport)
#   anything else       -> FAIL    (401/403/other 4xx: fail closed)
classify_http_code() {
  case "$1" in
    404) echo "ABSENT" ;;
    2??|3??) echo "EXISTS" ;;
    429|5??|000|"") echo "RETRY" ;;
    *) echo "FAIL" ;;
  esac
}

# probe_url <url> <user> <token>
# Prints ABSENT or EXISTS. Returns non-zero (hard fail) on FAIL or on exhausting
# the bounded retry budget for transient errors. Never returns success for an
# inconclusive result.
probe_url() {
  local url="$1" user="$2" token="$3"
  local max_attempts="${DATETIME_PROBE_MAX_ATTEMPTS:-4}"
  local backoff_base="${DATETIME_PROBE_BACKOFF_SECONDS:-2}"
  local curl_cmd="${DATETIME_PROBE_CURL:-curl}"
  local attempt=1 code="" verdict
  while [ "$attempt" -le "$max_attempts" ]; do
    code="$($curl_cmd -s -o /dev/null -w '%{http_code}' -I -u "$user:$token" "$url" 2>/dev/null || echo 000)"
    [ -n "$code" ] || code="000"
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
