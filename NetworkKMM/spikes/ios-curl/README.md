# NetworkKMM iOS curl spike

This is a bounded feasibility spike for the transport RFC. It does not switch
the production iOS engine away from Ktor Darwin/NSURLSession and it is not a
production transport implementation.

The harness cross-compiles OpenSSL, curl, and the existing `pbcurlwrapper`
sources for arm64 iOS Simulator, bundles Mozilla CA roots for the spike, then
installs a minimal app that performs a real HTTPS GET through `StartRequest`.
The harness performs the request twice to exercise connection sharing across
two separately created wrapper clients.

Run from `NetworkKMM` on an Apple Silicon Mac with a booted iOS Simulator:

```bash
./scripts/build-ios-curl-spike.sh --run
```

The expected success marker is:

```text
SLOCK_IOS_CURL_SPIKE completed passed=true
```

The Kotlin/Native binding probe is opt-in so normal publications and source
sets remain unchanged:

```bash
./gradlew :network:compileKotlinIosSimulatorArm64 -PiosCurlSpike --no-daemon
```

The same compile without `-PiosCurlSpike` validates that the default Darwin
configuration is unaffected.

## Measured result

Environment: Apple Silicon Mac, Xcode 26.1, arm64 iPhone Simulator, iOS 12.0
deployment target, OpenSSL 3.5.4, curl 8.16.0.

- Clean native build: 55.44 seconds.
- Cached native rebuild: 1.67 seconds.
- `libcrypto.a`: 8.6 MB.
- `libssl.a`: 1.9 MB.
- `libcurl.a`: 1.2 MB.
- `libpbcurlwrapper.a`: 200 KB.
- Dead-stripped simulator executable: 6.5 MB.
- First HTTPS request: HTTP 200, 559 bytes, about 503 ms total in the recorded
  run (about 399 ms TLS).
- Second HTTPS request: HTTP 200, 559 bytes, about 96 ms total, with DNS,
  connect, and TLS all reported as 0 because the shared connection was reused.
- Kotlin/Native cinterop plus `iosSimulatorArm64` Kotlin compile: successful.
- Existing host wrapper behavior suite: all checks passed.

## Findings

- Native compilation, static linking, app signing, simulator launch, wrapper
  callbacks, HTTPS, and connection reuse are feasible without rewriting the
  wrapper C++ implementation for iOS.
- curl 8.16.0 no longer contains the Secure Transport backend. The iOS SDK also
  does not expose a system libcurl development surface. A curl migration must
  therefore ship a TLS backend such as OpenSSL or rustls.
- OpenSSL did not automatically consume the app-bundled CA file through
  `SSL_CERT_FILE`. The spike added an explicit per-client CA path mapped to
  `CURLOPT_CAINFO`; that route succeeds.
- The spike initially exposed asymmetric callback ownership: buffered requests
  deleted caller memory while streaming requests only borrowed it. PR #60
  unified the wrapper contract to caller-owned callbacks, paired the Kotlin
  free with rebuilt OHOS libraries, and added stack-callback behavior coverage.
- The wrapper source previously selected a vendored `7.64.0-DEV` curl header
  through a quoted include while linking curl 8.16.0. The spike selects the
  current header explicitly; this is build hygiene, not iOS transport logic.
- A suspected process-global cleanup/share-handle lifetime risk did not
  reproduce in this two-client run. The second client successfully reused the
  first client's connection even after the first client was destroyed.

## Production boundary

The bundled `cacert.pem` proves that curl/OpenSSL can run, but it does not
preserve Darwin's SecTrust semantics, enterprise or user-installed roots, ATS,
system proxy/PAC discovery, credential challenges, VPN integration, or
NSURLSession's platform behavior. The spike also does not wire
`CurlRequestServiceIOS` into production, package device/x64 slices as an
XCFramework, validate background transfers, or establish HTTP/2/HTTP/3 parity.

The practical conclusion is that native build and cinterop are low-to-moderate
engineering work. Trust and proxy parity are the expensive product risks. Keep
Ktor Darwin/NSURLSession as the default unless transport unification becomes a
product goal that justifies those semantics and maintenance costs.
