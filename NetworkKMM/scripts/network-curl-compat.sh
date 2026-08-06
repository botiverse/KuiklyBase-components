#!/usr/bin/env bash

# curl 7.71 and earlier do not recognize --retry-all-errors. Keep the baseline
# retry count on every runner, and add the stronger retry mode only after the
# installed curl proves that it accepts the option.
network_resolve_curl_retry_args() {
  NETWORK_CURL_RETRY_ARGS=(--retry 2)
  if command curl --retry-all-errors --version >/dev/null 2>&1; then
    NETWORK_CURL_RETRY_ARGS+=(--retry-all-errors)
  fi
}

network_curl() {
  command curl \
    --silent --show-error \
    --connect-timeout 15 --max-time 60 \
    "${NETWORK_CURL_RETRY_ARGS[@]}" \
    "$@"
}
