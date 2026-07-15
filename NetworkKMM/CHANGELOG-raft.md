# NetworkKMM Raft fork changelog

## Unreleased (raft.27 stream lifecycle hardening)

- Version the native curl wrapper ABI with pointer+size+version `Start*V27`
  entry points and remove the old by-value symbols. Both old-caller/new-runtime
  and new-caller/old-runtime skew now fail at symbol/ABI validation instead of
  interpreting the raft.27 80-byte request layout as raft.26's 48-byte layout.
  Executable fixtures link frozen callers/runtimes in both skew directions and
  place a real 48-byte raft.26 request against an unreadable guard page while
  all three V27 entry points reject it before dereference.

- Streaming cancellation now survives both common task publication and native
  handle publication. Cancellation tombstones, CAS task transitions and a
  lock-owned OHOS handle registry prevent lost pre-start cancellation and
  prevent `Cancel` from dereferencing a handle concurrently with deletion.
  Terminal cleanup is identity-safe, late/duplicate cancellation cannot poison
  a reused request ID, and all cancellation owners still run when one throws.
- Native response-start is emitted only after a complete final HTTP header
  block. Informational and followed redirect blocks stay internal; HEAD, 204
  and non-2xx responses start correctly, while DNS/TLS/connect failures no
  longer fabricate an HTTP start. Cancellation after start or a body chunk
  suppresses subsequent chunks and still terminates exactly once.
  Proxy CONNECT headers are suppressed, Location field names are parsed
  case-insensitively, and terminal redirect-limit/protocol failures expose the
  actual final 3xx block without leaking followed intermediate redirects.
- Consumer callback failures are latched and contained before crossing native
  callback boundaries. The first response-start/chunk failure cancels the real
  transfer, suppresses later business callbacks and becomes a controlled
  terminal network failure.
- `NetworkCall` is the sole terminal arbiter for `await` and public completion
  callbacks. Cancellation, worker exceptions and late engine success race one
  atomic winner; callbacks remain exactly-once even before dispatch or while a
  custom middleware/engine is suspended. iOS C trampolines contain conversion
  and decode failures as well as user callback failures.
- Response streams use explicit sequential connect then final-origin-header
  budgets, an inter-chunk network-idle deadline, and an optional whole-transfer
  deadline. Curl starts the header clock only after pre-transfer connection,
  proxy-tunnel and TLS setup. Android/iOS Ktor apply the documented conservative
  `connect + responseHeaders` upper bound (their public API has no portable
  connection-ready event), plus a connect cap, socket-idle timeout and one
  logical opt-in request deadline shared across Android fresh retry. Healthy progressing large downloads are no
  longer implicitly bounded by the ordinary buffered request timeout.
- Executable contracts cover known-length and chunked bodies, informational
  headers, redirects, HEAD/204/non-2xx responses, pre/start/mid cancellation,
  callback failure, phase timeouts and cancel-vs-delete ownership stress.

## 0.1.0-raft.26 / 0.1.0-raft.26-ohos (caller-time hard deadlines)

- Android whole-request timeouts now begin at the public synchronous
  `VBTransportService.send*` entry, before any common-to-platform dispatcher
  handoff. The platform transport and its one allowed reused-H2 recovery retry
  consume the same monotonic caller-time budget, so queue starvation cannot
  silently extend a configured 30-second timeout into minutes.
- An independent daemon wall-clock scheduler atomically races completion with
  the deadline. A deadline winner cancels the real structured transport job
  (reaching Ktor OkHttp `Call.cancel()` / the HTTP/2 stream), releases the
  caller immediately without waiting for transport cleanup, suppresses late
  callbacks, and reports configured timeout, deadline elapsed time, callback
  delay, and cancellation result separately.
- Every unsuccessful reused-H2 attempt now releases its client-generation
  lease in a shared `finally`; caller cancellation is rethrown after cleanup
  rather than converted into a normal network failure. Logical task terminal
  state and the common task registry are concurrency-safe, and non-Android
  common timeout paths now actively cancel their transport instead of only
  returning a timeout response.
- Executable coverage includes a public-service request whose transport
  coroutine is blocked before OkHttp starts, deadline/completion and task-state
  races, caller cancellation and late callbacks, plus a real HTTP/2 sequence:
  warm connection, reused no-response-headers stream cancellation, active-call
  and generation-lease convergence, then a successful request on the same
  managed generation and physical connection.

## 0.1.0-raft.25 / 0.1.0-raft.25-ohos (Android reused-HTTP/2 recovery)

- Android's Ktor/OkHttp transport gains a default-off recovery path for reused
  HTTP/2 connections whose active streams stop receiving response headers. A
  watchdog starts only after request headers/body transmission completes and
  requires at least two independently matured calls on the same physical
  OkHttp connection before declaring it stale.
- Enabled origins are distributed round-robin across configurable, lazily
  created independent client/pool slots (five in the initial Alpha plan).
  Recovery rolls over only the affected slot to a fresh generation; retired
  clients drain their in-flight leases before closing, while unaffected slots
  continue serving traffic.
- Automatic cancellation and one sequential fresh-pool retry are restricted
  to replay-safe, bodyless GET/HEAD requests. Uploads, bodyful GET/HEAD calls,
  and all mutation methods are neither cancelled nor replayed by the watchdog.
- A rolling replacement budget caps pool churn, quarantines rate-limited stale
  slots while alternatives exist, and makes them probe-eligible after cooldown
  without creating slots beyond the configured count.
- Total-timeout accounting remains explicit across acquisition, request I/O,
  rollover, and retry; user/parent cancellation wins over watchdog-triggered
  cancellation. Monotonic configuration epochs prevent old requests from
  restoring superseded settings, and disabling recovery or switching engines
  retires existing recovery state safely.
- Transport diagnostics now report stale-h2 detection and quorum, connection
  origin/shard/generation/identity, drain and rollover-rate-limit state, fresh
  retry/result, and the observed no-response-headers duration.

## 0.1.0-raft.24 / 0.1.0-raft.24-ohos (explicit HTTP/3/QUIC curl gray lane)

- Android, iOS, and OHOS curl artifacts now build pinned nghttp3 1.17.0
  (`9635173e...b8f5`) beside curl 8.16.0 and OpenSSL 3.5.4, enabling the
  OpenSSL QUIC backend. Each build hard-gates `USE_NGHTTP2`,
  `USE_OPENSSL_QUIC`, and `USE_NGHTTP3`, then checks the pin-exact nghttp3 and
  OpenSSL QUIC references so an h2-only artifact cannot pass silently.
- `http3Enabled` is now a real explicit rollout gate. Runtime eligibility uses
  the linked artifact's `CURL_VERSION_HTTP3` feature bit; stale artifacts fail
  closed. Enabled requests use `CURL_HTTP_VERSION_3` (never `3ONLY`) and retain
  h2/h1.1 fallback. Disabled requests remain h2/h1.1 and use a separate shared
  connection pool so a previous h3 request cannot upgrade default traffic by
  connection reuse.
- The additive C wrapper surface reports capability and actual negotiated
  protocol without changing `CurlRequest` or `CurlResponse` layouts. Android
  JNI, iOS cinterop, and the OHOS VBTransport path propagate the explicit h3
  bit and expose the final protocol through `NetworkResponse.protocol`.
- Exact-artifact runtime gates cover real h3, a default h2 request after a live
  h3 connection, h3-to-h2 fallback, and the QUIC+TCP total-failure path.

## 0.1.0-raft.23 / 0.1.0-raft.23-ohos (platform-default curl trust restored)

- **Compatibility fix**: raft.20 made `VBTransportCurl.configure(...)` mandatory,
  which regressed OHOS — the only curl-default platform — where libcurl's
  compiled `CURL_CA_BUNDLE=/etc/ssl/certs/cacert.pem` was a device-verified,
  working trust source throughout the pre-raft.20 production line. Unconfigured
  apps shipped unable to network.
- Two-layer trust contract replaces the mandatory gate
  (`NetworkCurlTrustMode`): never-configured runs on the platform default
  where it is verified (OHOS only; Android/iOS keep the strict gate — their
  curl builds have no verified default). In default mode no CA path is handed
  to the wrapper and the proxy is pinned to explicit direct (`""` disables
  libcurl environment proxies), so semantics are deterministic.
- Explicit `configure()` is unchanged where it succeeds (app-owned verified
  CA/pin/proxy per request) and a FAILED configure now moves to a distinct
  `FAILED_CLOSED` state: requests stay refused and **never silently fall back
  to the platform default**. `clear()` returns to platform-default trust.
- Requests and engine capabilities carry the trust source
  (`platform_default` / `app_owned`) for diagnostics. Contract tests pin all
  four transitions; real-device default-CA HTTPS evidence is the mobile
  task #253 HOP-AL10 acceptance matrix (CI lanes cannot exercise the OHOS
  compiled default by construction).

## Shipped with 0.1.0-raft.20 (production curl trust, proxy, and rollout gates)

- All curl delegates now consume one verified `VBTransportCurl` runtime
  configuration: app-owned CA path + expected SHA-256 and an explicit
  direct/manual/Android-system/PAC-unresolved proxy decision. Missing,
  modified, unreadable, or mismatched bundles fail closed; failed
  reconfiguration clears stale state.
- The pinned CA staging helper uses the dated Mozilla `2026-05-14` snapshot and
  verifies SHA-256 before atomically publishing the file. Android emulator and
  iOS simulator runtime lanes cover valid, unknown, expired,
  hostname-mismatched, and wrong-CA certificates plus an observed HTTP CONNECT
  proxy request.
- `NetworkEngineRolloutConfig` provides stable basis-point cohorts, versioned
  salt, and immediate platform-default rollback. Selection diagnostics include
  the cohort bucket, requested/selected engines, and exact ineligibility reason.
- Curl capabilities now report app-owned trust/manual proxy truth and keep PAC,
  HTTPDNS, and HTTP/3 fail-explicit. Android `androidSystem()` now reads the
  current process proxy per request and routes PAC through Android's localhost
  forwarding proxy, preserving OS-owned script evaluation and fallback without
  adding a PAC interpreter to libcurl. Apple/OHOS remain explicit-unavailable;
  a first-result-only PAC mapping is not reported as support. HTTPDNS has no
  SNI-safe resolver contract yet; current native artifacts have no QUIC backend.
  Completion diagnostics expose negotiated protocol as `UNKNOWN` until native
  protocol extraction is implemented.

## 0.1.0-raft.22 / 0.1.0-raft.22-ohos (HTTP/2 for all curl engines)

> Version-trap note: **raft.21 is BURNED — never consume it.** Its publish
> run failed midway (GitHub Packages 409 on a retried PUT for the
> iosSimulatorArm64 klib after a partial server-side write), leaving that
> module partial and the root KMP metadata unpublished. Registry versions
> are immutable, so the coordinate cannot be repaired (same failure mode
> and remedy as raft.11 → raft.12). raft.22 is the identical content,
> republished cleanly.

## 0.1.0-raft.21 / 0.1.0-raft.21-ohos (HTTP/2 for all curl engines) — BURNED, see above

- All three curl engines negotiate HTTP/2 (#83, task #30): nghttp2 1.64.0
  (SHA-256 pinned) statically linked into the Android per-ABI `.so`, the
  iOS XCFramework slices and the OHOS wrapper — curl's default
  HTTP/2-over-TLS ALPN activates with no API change; h1.1-only servers
  keep working. The OHOS production engine gains connection multiplexing;
  Android/iOS curl opt-ins reach parity with their platform defaults.
- Guard rails from the review cycle: per-line archive symbol gates
  (pin-exact `nghttp2_session_client_new3`; Mach-O end-anchored) plus the
  iOS config-layer `USE_NGHTTP2` primary gate; iOS device-slice feature
  detection fixed via compile-only try_compile (device SDK cannot link
  test executables — the silent h1-only fallback this would have caused
  is exactly what the gates now catch).
- Wrapper logs the negotiated protocol at completion
  (`transport_protocol http_version=h2/...`) — structured
  `VBTransportElapseStatistics.protocol` wiring for the curl ends rides
  the HTTP/3 line (task #31).
- Artifacts are combined-source builds also carrying the #81 rollout
  proxy/CA seams (verified per-end by symbol audit).

## 0.1.0-raft.20 / 0.1.0-raft.20-ohos (curl rollout gates + Android connection limits)

- curl production rollout gates (#81, task #25): app-owned CA bundle
  wiring (`ca/curl-ca-bundle.env` pin), rollout/gray seams and docs for
  taking the curl engines to production — groundwork for the h2/h3 line.
- Android OkHttp `maxRequestsPerHost` lifted 5 → 16 (#85, task #587):
  raft.19's tracing evidence showed real permit-wait saturation on burst
  (6th+ request queueing on cold Home/Thread); dispatcher limits now match
  actual concurrency. `maxRequests=64` unchanged, pinned by test.
- curl HTTP/2 (task #30) intentionally NOT in this coordinate — it ships
  next with its combined-source artifact refresh (PR #83 terminal line).

## 0.1.0-raft.19 / 0.1.0-raft.19-ohos (transport tracing + contract single-source)

- Transport phase tracing (#82): `VBTransportElapseStatistics` gains
  `clientQueueTimeMs`, `requestPreparationTimeMs`, `transportQueueTimeMs`,
  `protocol`, `reusedConnection` and `connectionAttemptCount`; the Android
  OkHttp path fills them from real engine signals. Real-device smoke shows
  `protocol=h2` against api.raft.build.
- iOS curl fix-forward (#78): handle-registry cancel now runs inside the
  registry lock (native use-after-free window closed); GET/HEAD
  streaming-body rejection converges on one commonMain contract helper
  (RFC #67/#68 canonical message + CODE_NETWORK_ERROR) on Android and iOS;
  `VBTransportIosCurl.caInfoPath` is atomic-backed.
- The GET/HEAD contract message is literally single-source across all three
  ends (#79): OHOS references the same commonMain constant.
- **Packaging: the default Android AAR no longer embeds
  `libnetworkkmmcurl.so`** (artin: Android production stays on OkHttp).
  Artifacts remain committed and CI-gated; the Android CURL delegate
  fail-closes to Ktor when the native library is absent. The OHOS `-ohos`
  coordinate is unchanged (curl remains its production engine).

## 0.1.0-raft.18 / 0.1.0-raft.18-ohos (production iOS curl transport)

- iOS gains the production curl transport (task #23): `engine=curl` is an
  explicit opt-in delegate behind the typed selector — buffered requests,
  streaming downloads, streaming uploads with progress, cancellation (both
  pre-start and cross-thread external), callback-failure latching, and
  delegate-owned capabilities. The default iOS engine remains Ktor/Darwin;
  the CURL delegate registers only when the native artifact loads AND an
  app-owned CA path is set (`VBTransportIosCurl.caInfoPath`) — fail-closed
  without one.
- The AAR-style committed-binary line now covers iOS (task #24):
  `network/libs/ios/NetworkKMMCurl.xcframework` — per-slice static library
  (shared pbcurlwrapper + pinned OpenSSL 3.5.4/curl 8.16.0 merged), built and
  symbol-verified by the `networkkmm-ios-native` macOS lane, consumed by the
  KMP ios targets via cinterop. Phase 2's three-end native line is complete.
- Blocking curl performs and upload producers run on dedicated isolated
  thread pools (`NetworkKmmIosCurlPerform` ×4 / `NetworkKmmIosCurlUploadWriter`
  ×2) — never the shared `Dispatchers.Default`; the concurrency runtime gate
  pins more concurrent uploads than the perform pool width.
- Repo hygiene: upstream sample/demo leftovers and an accidentally committed
  coredump removed (task #26); the Android native CI lane runs in the
  `kuikly-ci-images/android-ndk` container with SHA-256-pinned baked
  toolchain + OpenSSL/curl sources (task #28).

## 0.1.0-raft.17 / 0.1.0-raft.17-ohos (typed selector and Android curl transport)

- JNI shim: header-loop `JStringUtfChars` RAII wrappers now release their
  chars before `DeleteLocalRef` runs (ART CheckJNI aborts on the reversed
  order); caught by the task #22 real-emulator gate, which is now permanent
  connected coverage (all eight runtime acceptance cases on a 16 KB arm64
  AVD).

- Shared wrapper (task #24, RFC D-5): `cancel_flag_` is now `std::atomic<bool>`
  (relaxed) — `Cancel()` writes from arbitrary threads while the perform
  thread reads in callbacks; the plain `bool` was a C++ data race. All three
  entry points also honor a PRE-SET cancel deterministically before perform
  starts (CURLE_ABORTED_BY_CALLBACK, exactly one callback) instead of relying
  on the first progress tick.
- OHOS converges on the RFC #67 fail-explicit baseline: a streaming body on
  GET/HEAD now returns a classified transport error. This replaces the prior
  "buffered fallback", which was in fact a silent body drop (the wrapper
  skips the body for GET/HEAD entirely).
- The network AAR now embeds the production Android curl artifact: per-ABI
  (arm64-v8a, x86_64) `libnetworkkmmcurl.so` — shared pbcurlwrapper + the
  #22 JNI shim, statically linked against pinned OpenSSL/curl (SHA-256
  verified downloads) — built by `scripts/build-android-curl.sh` via the
  `networkkmm-android-native` workflow and committed like the OHOS `.so`
  line. This also gives the JNI shim its first per-PR compile lane. The
  Android CURL delegate resolves once the AAR ships the artifact; no CA
  bundle is shipped (the engine takes an app-provided path and stays
  fail-closed without one — Phase 3 #25 owns bundle strategy).

- Added `NetworkTransportEngine.KTOR/CURL` and a typed per-request selector at
  the `NetworkEngine` routing boundary. External `"ktor" | "curl"` values map
  once through `NetworkEngineSelection.fromExternalConfig`; business and
  transport code do not perform raw string checks.
- Current platform engines remain the default: Android/iOS use Ktor and OHOS
  uses curl. Unsupported or disallowed requests fail closed to that platform
  default. A dynamic `forcePlatformDefault` flag provides remote rollback
  without rebuilding the `NetworkClient`. Selection is latched per
  `NetworkCall`, so retries never switch engines mid-call; rollback applies to
  the next call.
- Selection and completion diagnostics expose requested/selected/default
  engine, fallback reason, actual engine capabilities, status/error, and a
  copy of `VBTransportElapseStatistics`.
- `NetworkClient.downloadStream` now routes through `NetworkEngine`, so future
  Android/iOS curl transports cannot be selected for buffered/upload requests
  while silently bypassed by streaming downloads.
- Android now has an injectable curl `NetworkEngine` plus a production JNI
  bridge for buffered requests, streaming downloads, streaming uploads, and
  cross-thread cancellation. The delegate is registered only when
  `libnetworkkmmcurl.so` loads successfully, so rollout still fails closed to
  Ktor when the standard AAR does not contain the native artifact.
- Android curl owns its streaming/progress capabilities, maps curl/HTTP errors
  and timing through the shared response contract, supports an app-provided CA
  bundle path, and preserves cancellation arriving both before native handle
  publication and while a transfer is running. JNI callback names are retained
  through consumer ProGuard rules.

## 0.1.0-raft.16 / 0.1.0-raft.16-ohos (streaming upload end-to-end)

> Version-trap note: the raft.15 coordinate was published the morning of
> 2026-07-09 (the Kotlin 2.1.21 revert, consumed by mobile #422) — BEFORE
> the streaming slices merged. GitHub Packages versions are immutable and
> the publish skip-probe green-skips existing versions, so raft.15 does NOT
> contain issue #8. raft.16 is the first coordinate that ships it.

### Streaming upload (issue #8, all three slices)

- **Request bodies stream on every platform — no full buffering.**
  Stream/FileRef bodies go out as a true byte stream: known length → real
  Content-Length, unknown → chunked, per-chunk progress preserved.
  - Slice 1 (#48): Android/iOS via ktor `WriteChannelContent`; buffered
    interface fallback keeps a classified-error contract (a failing
    writeBody reaches the callback, never a silent hang).
  - Slice 2 (#53): multiparts containing streaming parts go out as a
    composite stream. `NetworkMultipartFraming` is the single wire-format
    source — streamed and buffered multiparts are byte-identical (pinned by
    test), so servers cannot tell the difference. All-scalar multiparts keep
    the raft.8-validated buffered path.
  - Slice 3 (#54): OHOS true streaming via curl `CURLOPT_READFUNCTION`
    (`CurlUploadSource`/`StartUploadRequest` in the rebuilt wrapper .so) with
    a push→pull bridge on a dedicated writer pool.
    `platformRequestBodyStreaming = true` on ohosArm64.
- `NetworkEngineCapabilities.requestBodyStreaming` now reports truthfully on
  android/ios/ohos. Semantics shared by all ends: the upload source is
  one-shot — a redirect re-POST/auth retry fails honestly
  (`CURLE_SEND_FAIL_REWIND` on OHOS) instead of silently resending a
  truncated body; `Expect: 100-continue` is disabled on OHOS for parity with
  the ktor/OkHttp transports.

## 0.1.0-raft.15 / 0.1.0-raft.15-ohos (normal tree back to Kotlin 2.1.21 — consumer klib ABI)

> Published 2026-07-09 morning, before the streaming slices merged — see the
> raft.16 note above. Contains ONLY the toolchain revert below.

- **raft.14's iOS klibs are unusable by mobile — do not consume raft.14 on
  iOS.** The normal tree's Kotlin 2.2.21 produced klibs with ABI 2.2.0, and
  Kotlin/Native klib ABI is strictly forward-only: mobile's Kotlin 2.1.21
  iOS compiler rejects them (caught by consumer verification on mobile PR
  #422; Android and the -ohos line were unaffected). The producer toolchain
  must not exceed the consumer's Kotlin — the jar-metadata N+1 rule does NOT
  apply to K/N klibs.
- Normal tree back to Kotlin **2.1.21**, OkHttp back to **5.0.0-alpha.14**
  (5.x stable forces stdlib 2.2.21 which breaks the 2.1.21 metadata
  transform). fastFallback unaffected. Both move to stable/2.2.x together
  with mobile's own Kotlin bump — recorded in the task #18 trigger list.
- Dual-tree structure, publish lanes, and skip probes unchanged from raft.14.


## 0.1.0-raft.14 / 0.1.0-raft.14-ohos (dual build trees — kuikly convention, task #18)

- **The repo now has two build trees** (like kuikly-open): the normal tree
  (`settings.gradle.kts`, upstream Kotlin 2.1.21, android + ios targets,
  official dependencies) and the OHOS tree (`-c settings.ohos.gradle.kts`,
  KBA 2.0.21 toolchain, ohosArm64 + the ohos-runtime/sample/host-native-test
  modules, KBA coroutines/atomicfu declared directly).
- **Same coordinates, two versions**: the normal tree publishes
  `com.tencent.kuiklybase:network:<v>` (root module variants: android + ios),
  the OHOS tree publishes `<v>-ohos` with its own root module (variants:
  ohosArm64). Consumers select the tree by version, exactly how mobile
  already consumes kuikly-open (`…-2.1.21` vs `…-2.0.21-ohos`). Consumer
  change: mobile's `build.ohos.gradle.kts` switches its network and
  ohos-runtime-plugin coordinates to the `-ohos` suffix; the normal tree
  needs no change.
- **The #42 force()/versionMapping machinery is gone**: each tree declares
  its own dependencies plainly, so published metadata is honest by
  construction. The transportLaunch expect/actual seam stays (the ohos
  actual keeps the fork's `track = true` and only compiles in the OHOS tree).
- **OkHttp unpinned to stable 5.4.0**: the alpha.14 ceiling existed only
  because the single tree compiled everything with the KBA 2.0.21 toolchain;
  the normal tree's Kotlin 2.1.21 reads OkHttp's Kotlin 2.2 metadata fine.
- CI: the PR lane gains an `ohos-tree` job (KBA compile + publication
  metadata assertions: `-ohos` suffix + KBA dependency declarations); the
  publish workflow splits into normal-tree jobs (android, ios, root
  metadata) and an OHOS-tree job (ohosArm64 + its root + runtime modules).


## 0.1.0-raft.13 (iOS coroutines linkage fix, full chain logging, knoi removal)

- **iOS/Android klibs reference official kotlinx.coroutines/atomicfu again**
  (#42): the transport source used the KBA fork's `launch(track = true)`
  overload in common code, baking KBA-only symbols into every platform's
  klib — on iOS, where consumers link upstream coroutines, each request died
  at the call site with `IrLinkageError` (the "stuck at Signing in..." P1;
  broken since the dual-toolchain split, surfaced by the first real iOS
  login attempt). Fixed three layers down: an expect/actual `transportLaunch`
  seam (ohos actual keeps `track = true`, everything else pure upstream),
  ohos-scoped `force()` to the KBA versions (the only line with an ohos
  klib), and per-publication `versionMapping(fromResolutionOf(
  "ohosArm64CompileKlibraries"))` so the published ohos variant honestly
  declares KBA while ios/android declare upstream. PR CI now asserts the
  ohos metadata on every change.
- **Full request-chain log brackets (Android + iOS ktor, execute + stream)**
  (#41): send → response-received (status/contentLength/elapsedMs) → body
  read/stream complete (bytes/totalElapsedMs). With raft.9's classified
  failure reasons, every request names the layer it died in. Send logs now
  print header KEYS only — the old full-header logging would have leaked
  Authorization values the moment hosts wire the info logger.
- **knoi plugin removed from :network** (#40): vestigial template carry-over
  (zero knoi references in any source set); drops the per-target KSP no-op
  tasks and the Darwin lane's dependency workarounds.
- OHOS native `.so` unchanged from raft.11/12 line (Kotlin-side changes only).

## 0.1.0-raft.12 (clean republish of raft.11 content — CONSUME THIS, NOT raft.11)

- **raft.11 coordinates are poisoned — do not consume.** Its first publish run
  uploaded the Android/OHOS/iosX64 artifacts built with the okhttp
  5.0.0-alpha.16 pin, then died on the commonMain metadata ICE (stdlib 2.1.21
  leak), leaving no KMP root module. GitHub Packages versions are immutable
  (retry = 409 Conflict), so the version number moves forward instead.
- raft.12 = the raft.11 changelog content (OHOS connect budget + HE pin +
  phase log) + the OkHttp pin corrected to **5.0.0-alpha.14** (built with
  Kotlin 1.9.23, stays below the project stdlib; plain JVM artifact, so the
  androidx.startup edge disappears) + the PR test lane now compiles common
  metadata so this class of failure gates at PR time.

## 0.1.0-raft.11 (OHOS connect budget + Happy Eyeballs pin + slow-transfer phase log)

- **OHOS connect budget**: the curl wrapper now sets
  `CURLOPT_CONNECTTIMEOUT_MS = 3000` (aligned with the ktor transports' raft.9
  connect cap; libcurl's default connect timeout is 300s) and pins
  `CURLOPT_HAPPY_EYEBALLS_TIMEOUT_MS = 200` explicitly instead of trusting the
  libcurl default. Ships the rebuilt `.so` (networkkmm-ohos-native run
  28931135301, committed as fd91f7b).
- **Slow-transfer phase log (OHOS)**: failed or ≥3s transfers log a one-line
  curl phase breakdown (`transport_timing … dnsMs/connectMs/tlsMs/ttfbMs/
  redirectMs/totalMs`) from the ElapseStats the wrapper already collects —
  "connect slow vs transfer slow" becomes one log line. No API change; the
  timings were already exposed via `NetworkResponse.timing`.
- Note: **0.1.0-raft.10 was never published** — its content (Android engine
  switched to Ktor-OkHttp with `fastFallback = true`, kill switch
  `VBTransportAndroidEngine.okHttpEnabled`) ships for the first time in this
  release. See the raft.10 changelog entry below for details.

## 0.1.0-raft.10 (Android engine: OkHttp + fastFallback)

- **Android transport engine switched from Ktor `Android` (HttpURLConnection)
  to Ktor `OkHttp` with `fastFallback = true`** (RFC 8305 Happy Eyeballs):
  IPv6/IPv4 connect attempts race in parallel (~250ms stagger) instead of a
  black-holed family serially exhausting the connect budget. This is the root
  fix behind raft.9's 3s connect stopgap — on dual-stack networks with one
  broken family, cold connections now settle in the sub-second range instead
  of paying the 3s fail-fast step. OkHttp is forced to 5.0.0-alpha.14
  (fastFallback exists since 5.0.0-alpha.4; the ktor-client-okhttp 2.3.7 POM
  only pulls 4.12.0; every 5.x stable ships Kotlin 2.2 metadata the KBA
  2.0.21 toolchain cannot read, and alpha.16's kotlin-stdlib 2.1.21 wins
  dependency resolution and ICEs the commonMain metadata compiler — alpha.14
  is built with Kotlin 1.9.23 and stays below the project stdlib).
- **Kill switch**: `VBTransportAndroidEngine.okHttpEnabled = false` (before the
  first request, e.g. app startup) falls back to the legacy HttpURLConnection
  engine. Default is OkHttp. Ktor API surface and the raft.9 per-request
  timeout wiring (`HttpTimeout` plugin) are unchanged and apply to both
  engines.
- Behavioural deltas to regression-test on the consumer side: redirects,
  connection pooling, system-proxy handling (OkHttp reads
  `java.net.ProxySelector` like HttpURLConnection, but PAC/edge cases differ),
  auth and multipart upload paths. iOS/OHOS transports are untouched.

## 0.1.0-raft.9 (EOF-safe body reads, classified failure reasons, connect-timeout decoupling)

- **Connect timeout decoupled from the request total timeout (Android + iOS ktor
  transports)**: connect/request/socket timeouts were all bound to the same
  totalTimeout (30s+). HttpURLConnection tries addresses serially with no Happy
  Eyeballs, so a black-holed address family (IPv6 behind an IPv4-only proxy)
  burned the whole budget before falling back — measured as 5/15/30s
  cold-connection ladders (16s channel open vs 345ms warm repeat). Connect now
  gets its own 3s budget (`min(3s, totalTimeout)`); dead-family cold
  connections pay one ~3s step. Stopgap, not cure: zero-cost parallel racing
  (RFC 8305) is engine-level, tracked in the transport-engine RFC. OHOS curl
  wrapper equivalents (CURLOPT_CONNECTTIMEOUT_MS + Happy Eyeballs + CURLINFO
  timing markers) need a .so rebuild and ship in raft.10.
- **EOF-safe body reads (Android + iOS ktor transports)**: `ByteReadChannelWrapper.readAvailable`
  treated ktor's `-1` EOF return as progress, so any response whose delivered
  byte count differed from `Content-Length` (early close, or a transparently
  decompressed body whose header still carried the compressed length) spun in
  the read loop until the request timeout and surfaced as an opaque transport
  error ("HTTP 0") — the root cause behind avatar image downloads all failing
  (botiverse/mobile#440). `Content-Length` is now a hint: short delivery
  returns the bytes that arrived, over-delivery drains to EOF instead of
  truncating, and a mismatch is logged at error level with declared/actual
  sizes and `Content-Encoding`. OHOS is unaffected (native curl write-callback
  assembly; short bodies already surface as `CURLE_PARTIAL_FILE`).
- **Classified failure reasons (all transports)**: failed transfers now carry a
  reason tag in `errorMessage` — `[timeout]` / `[dns]` / `[tls]` /
  `[connection_lost]` / `[connect]` / `[cancelled]` / `[engine]` — via a common
  exception classifier on Android/iOS and a CURLcode map on OHOS sharing the
  same vocabulary. No API change; the tag flows through the existing
  `errorMessage` field so callers' logs become diagnosable without new wiring.
- OHOS native `.so` is unchanged from raft.7 (Kotlin-side changes only).

## 0.1.0-raft.8 (Android multipart Content-Type fix)

- **Android multipart uploads**: the ktor Android transport overrode the request
  `Content-Type` with `application/octet-stream` for any non-JSON body, dropping
  the caller's `multipart/form-data; boundary=...` header — the server's multer
  saw no multipart and returned `No files provided`. It also set the header twice
  (ktor's `header()` and `contentType()` both append), producing a duplicate
  Content-Type. The transport now only defaults the `Content-Type` when the
  request has no explicit one (`hasExplicitContentType`); multipart requests keep
  their raw header with the exact boundary, sent once. Android-only (ktor); the
  OHOS native `.so` is unchanged from raft.7.

## 0.1.0-raft.7 (OHOS libcurl content-encoding codecs — gzip/deflate/br/zstd)

- **All content-encoding codecs on OHOS**: the OHOS libcurl was built with
  `CURL_ZLIB=OFF`/`CURL_BROTLI=OFF`/`CURL_ZSTD=OFF`, so it only understood
  `identity` and failed any compressed response with `CURLE_BAD_CONTENT_ENCODING`
  (61) — e.g. a Cloudflare-fronted API serving Brotli, which blocked login. As a
  general-purpose network service it must decode any standard encoding, so
  `build-ohos-native.sh` now cross-compiles zlib 1.3.1 + brotli 1.1.0 + zstd 1.5.6
  and links them into `libcurl.a`/`libpbcurlwrapper.so`. The build fails loudly
  (nm symbol check) if any decoder is missing, so a codec can't silently regress
  to identity again.
- **Transparent decode in the wrapper**: the wrapper now lets libcurl advertise
  and decode every built-in codec (`CURLOPT_ACCEPT_ENCODING` empty = all). A
  caller-supplied `Accept-Encoding` is routed through libcurl's decoder instead
  of a raw header (no duplicate header, and `identity` is still honoured).
  Streaming still forces `identity` so `Content-Length` matches the bytes
  delivered to `onChunk` (determinate progress). The raft.5 half-manual gzip pass
  is removed — libcurl decompresses in place.
- Native `.so` rebuilt; the cinterop header is unchanged.

## 0.1.0-raft.6 (streaming completeness — response headers + OHOS native)

- **Response headers at stream start**: `NetworkClient.downloadStream` now
  reports the status code, Content-Length and headers via `onResponseStart`
  the moment they are known — before the first chunk — so callers can show
  determinate download progress. Android/iOS fire it right after ktor returns
  the response; the buffering fallback fires it once the body is read.
- **OHOS native streaming (fork #8 phase 2)**: the libcurl wrapper previously
  buffered the whole response into a std::string. A parallel `StartStreamRequest`
  entry now streams each libcurl write straight to Kotlin via `onChunk` (no
  buffering), fires `onResponseStart` when headers are ready and delivers a
  body-less completion — so OHOS matches Android/iOS instead of falling back to
  the full-buffer path. Streaming requests negotiate identity (not gzip), since
  chunks are not incrementally inflated. The native `.so` is rebuilt to carry
  the new `StartStreamRequest` symbol.

## 0.1.0-raft.5 (gzip on OHOS + streaming download)

- **gzip on OHOS**: the libcurl wrapper only negotiates and decompresses gzip
  when the request carries `Accept-Encoding: gzip`; otherwise it falls back to
  `identity` and receives an uncompressed response. The OHOS `buildRequestHeader`
  path now defaults that header (next to the existing default Content-Type), so
  gzip is unified across platforms (Android/iOS already handle it transparently
  in their engines). Kotlin-only — the wrapper already decompresses — no native
  `.so` rebuild. An explicit caller `Accept-Encoding` is respected.
- **Streaming download (fork #8, Android/iOS)**: `NetworkClient.downloadStream(
  request, onChunk, onComplete)` delivers the response body chunk-by-chunk off
  ktor's response `ByteReadChannel` (16 KiB reads) instead of buffering the whole
  payload; `onComplete` carries status/headers/error only. Request middlewares
  and the current auth token are applied up front (no mid-stream refresh/retry —
  a stream cannot be replayed); the returned `NetworkCall` cancels it. Wired
  through `IVBTransportService.requestStream` (interface default falls back to the
  full-buffer `request()` and hands the whole body over as one chunk, so every
  platform works immediately), `VBTransportTask.streamRequest`, and
  `VBTransportService.streamRequest`. OHOS keeps the buffering fallback until its
  libcurl write callback streams chunks into Kotlin (a later native-rebuild
  change).

## 0.1.0-raft.4 (connection pooling on all three platforms)

- **Android/iOS**: previously a NEW ktor HttpClient was constructed for every
  request (and never closed): no connection reuse, no TLS session cache, plus
  an engine leak. Both platforms now share one lazily-created client with the
  HttpTimeout plugin installed; per-request timeouts move to ktor's
  request-level `timeout {}` block.
- **OHOS**: each request creates a fresh curl easy handle, which meant a fresh
  TCP + TLS handshake every time. The wrapper now attaches every easy handle
  to a process-wide `curl_share` (CURL_LOCK_DATA_CONNECT / DNS / SSL_SESSION,
  mutex-guarded), pooling connections, DNS entries, and TLS sessions across
  requests. No header/ABI change, but the native `.so` must be rebuilt to
  pick up the pooling (CI commit_binaries before publish).

Prerequisite for migrating Slock's Android/iOS HTTP onto NetworkKMM without
a connection-pooling regression (Slock task #12 step 2).

Also in this version — behavior-contract tests for the wrapper
(`tests/wrapper/`, run in CI on every PR: host build of the same sources
against a local server; locks status passthrough, error bodies, timeouts,
redirects, POST bodies, and share pooling). Writing them immediately
flushed out three latent wrapper bugs, all fixed here:
- `cancel_flag_` was never initialized — an indeterminate value makes
  ProgressCallback abort transfers (CURLE_ABORTED_BY_CALLBACK) at random.
- `curl_response_` started as a wild pointer — the destructor deletes it,
  so a client destroyed before its first response freed garbage.
- HeaderCallback passed an `int32_t*` to CURLINFO_RESPONSE_CODE, which
  writes a `long` — 4 bytes of stack corrupted on every response header
  on LP64 platforms.

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
