# NetworkKMM Raft fork changelog

## 0.1.0-raft.3 (OHOS: surface real HTTP status codes)

Critical fix: on OHOS the wrapper only returned the CURLcode (0 = transfer
completed), not the HTTP status, and `statusCodeFromErrorCode` blanket-mapped
a completed transfer to 200. Every 401/403/5xx response was therefore
reported as a 200 whose body was the error JSON — Slock's auth middleware
never saw a 401, token refresh never fired, and an expired session bricked
the app ("no messages yet" everywhere) until re-login.

- `CurlResponse` gains an explicit `httpCode` field (CURLINFO_RESPONSE_CODE)
  set by the wrapper; the ohosArm64 `updateResponse` reports it whenever the
  transfer itself completed. Transport errors keep the CURLcode semantics.
- ⚠️ **ABI change**: `CurlResponse` layout changed — the native libraries MUST
  be rebuilt from source for this version (CI: networkkmm-ohos-native
  workflow with commit_binaries) BEFORE publishing. A raft.3 klib running
  against a raft.2 libpbcurlwrapper.so will misread the response struct.
- Android/iOS paths untouched (their transports already report HTTP statuses).

## 0.1.0-raft.2 (OHOS TLS certificate verification)

Enables real TLS trust on OHOS. The wrapper now sets
`CURLOPT_SSL_VERIFYPEER=1` / `CURLOPT_SSL_VERIFYHOST=2`, so the server
certificate chain and hostname are verified — closing the MITM hole that
raft.1 shipped with.

- Trust anchors come from the **OHOS system CA store**: `build-ohos-native.sh`
  builds OpenSSL with `--openssldir=/etc/ssl` and curl with
  `CURL_CA_BUNDLE=/etc/ssl/certs/cacert.pem` / `CURL_CA_PATH=/etc/ssl/certs`, so
  the default trust path is compiled to where OpenHarmony ships its system
  certificates. The wrapper only sets `VERIFYPEER`/`VERIFYHOST` — no CA file is
  bundled or set at runtime (approach follows the reference OHOS curl build).
- The native build is now driven by CI
  (`.github/workflows/networkkmm-ohos-native.yml`) using the
  `harmonyos-ci-image`, so the shipped `.so`/`.a` always match the sources.

## 0.1.0-raft.1 (OHOS native crash fix)

Fixes the OHOS SIGSEGV in `libpbcurlwrapper.so` `StartRequest` (fault address
`0x54534f50` = ASCII "POST"): the checked-in prebuilt `libpbcurlwrapper.so`
(dated 2025-06-12) was older than `curl_wrapper.h`, which gained a `method`
field in `CurlRequest` on 2026-06-25 — an ABI mismatch that crashed on the
first request, including login.

- Rebuilt `libpbcurlwrapper.so` from source against the current wrapper header,
  with freshly cross-compiled OpenSSL 3.5.4 + curl 8.16.0 (OHOS
  `aarch64-linux-ohos` toolchain). Reproducible via
  `NetworkKMM/scripts/build-ohos-native.sh`.
- libcurl is built with WebSocket enabled (`curl_ws_send`/`curl_ws_recv`
  present) so the planned curl-ws realtime transport needs no further native
  rebuild.

**SECURITY — known insecure, not for release.** This version keeps the
wrapper's existing `CURLOPT_SSL_VERIFYPEER=0` / `CURLOPT_SSL_VERIFYHOST=0`, i.e.
HTTPS is encrypted but the server certificate is NOT verified (no MITM
protection). It exists only to validate the crash fix and login end to end.
Certificate verification (`VERIFYPEER=1`/`VERIFYHOST=2` + CA bundle) lands in
the next version and is a hard requirement before any production use.

## 0.1.0-raft.0

Initial OHOS fork. **Broken on OHOS** — ships a prebuilt
`libpbcurlwrapper.so` whose ABI predates `curl_wrapper.h`; every request
crashes. Superseded by 0.1.0-raft.1.
