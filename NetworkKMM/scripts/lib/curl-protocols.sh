#!/usr/bin/env bash
# Shared libcurl protocol policy for the Android, iOS and OHOS native builds.
#
# One list for all three carriers so an upgrade cannot change one platform's
# protocol set without changing the others. We used to pass HTTP_ONLY on
# Android/iOS; curl 8.22 made that branch also disable WebSocket with a plain
# set() that shadows the cache, so -DCURL_DISABLE_WEBSOCKETS=OFF could not win
# and raft.46 shipped without ws/wss (the curl Socket.IO realtime client needs
# them). Disable the unwanted protocols one by one instead and keep WebSocket.
#
# Usage, inside a cmake invocation's argument list:
#   "${CURL_PROTOCOL_CMAKE_FLAGS[@]}"
# and after configure, with the generated lib/curl_config.h:
#   curl_assert_websocket_config <curl_config.h>
# and after build, with the symbol table text of libcurl.a:
#   curl_assert_websocket_symbol <nm output>  (uses CURL_WEBSOCKET_SYMBOL)

# Protocols we never ship. Keep in sync with what curl's HTTP_ONLY implied in
# 8.16 (everything but HTTP/HTTPS/WS/WSS), plus SMB which some builds had on.
CURL_DISABLED_PROTOCOLS=(
  DICT FILE FTP GOPHER IMAP IPFS LDAP LDAPS MQTT POP3 RTSP SMB SMTP TELNET TFTP
)

CURL_PROTOCOL_CMAKE_FLAGS=()
for _proto in "${CURL_DISABLED_PROTOCOLS[@]}"; do
  CURL_PROTOCOL_CMAKE_FLAGS+=("-DCURL_DISABLE_${_proto}=ON")
done
CURL_PROTOCOL_CMAKE_FLAGS+=("-DCURL_DISABLE_WEBSOCKETS=OFF")
unset _proto

# A public WebSocket entry point; present in libcurl.a only when ws/wss are built.
CURL_WEBSOCKET_SYMBOL="curl_ws_recv"

# Fails when the configured curl has WebSocket disabled.
curl_assert_websocket_config() {
  local config_h="$1"
  if grep -q '#define CURL_DISABLE_WEBSOCKETS 1' "$config_h"; then
    echo "curl_config.h CURL_DISABLE_WEBSOCKETS is set: the realtime client needs wss://" >&2
    return 1
  fi
  echo "curl_config.h CURL_DISABLE_WEBSOCKETS: not set (WebSocket enabled)"
}

# Fails when the built libcurl.a does not define the WebSocket entry point.
# $1 is the text output of nm/llvm-nm over libcurl.a (pass it, do not pipe:
# grep -q short-circuiting a producer trips pipefail into a false negative).
curl_assert_websocket_symbol() {
  local symbols="$1"
  if grep -qw "$CURL_WEBSOCKET_SYMBOL" <<<"$symbols"; then
    echo "libcurl WebSocket (realtime): ENABLED (${CURL_WEBSOCKET_SYMBOL} defined)"
  else
    echo "libcurl WebSocket (realtime): MISSING — ${CURL_WEBSOCKET_SYMBOL} not defined" >&2
    return 1
  fi
}
