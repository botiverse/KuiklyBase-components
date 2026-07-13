# Advanced NetworkClient features

This page covers the `NetworkClient` features used by shared libraries and app shells that need more
than a one-off request.

## Add auth and token refresh

`NetworkAuthConfig` injects the current token and deduplicates concurrent refresh calls for matching
401-style responses. The core library only provides hooks; app-specific token storage stays outside
NetworkKMM.

```kotlin
class TokenProvider : NetworkTokenProvider {
    override suspend fun currentToken(request: NetworkRequest): String? = loadToken()

    override suspend fun refreshToken(
        request: NetworkRequest,
        response: NetworkResponse
    ): String? = refreshTokenOnce()
}

val client = NetworkClient(
    NetworkClientConfig(
        auth = NetworkAuthConfig(
            tokenProvider = TokenProvider(),
            headerName = "Authorization",
            refreshStatusCodes = setOf(401),
            formatToken = { token -> "Bearer $token" }
        )
    )
)
```

If a request already sets the auth header, `NetworkClient` does not overwrite it.

## Wrap calls with ordered interceptors

Use interceptors when behavior needs to run around each engine attempt in a predictable order:

```kotlin
val client = NetworkClient(
    NetworkClientConfig(
        interceptors = listOf(
            object : NetworkInterceptor {
                override suspend fun intercept(chain: NetworkInterceptorChain): NetworkResponse {
                    val startedAt = currentTimeMillis()
                    val response = chain.proceed(
                        chain.request.apply { setHeader("X-Trace-Source", "shared") }
                    )
                    recordNetworkTiming(chain.request, response, currentTimeMillis() - startedAt)
                    return response
                }
            }
        )
    )
)
```

Interceptors run in list order before the engine and unwind in reverse order after `proceed`.
`requestMiddlewares` and `responseMiddlewares` remain useful for simple request mutation or response
observation.

## Observe upload and download progress

`NetworkRequest.progress` accepts upload and download callbacks:

```kotlin
val request = NetworkRequest(
    method = VBTransportMethod.POST,
    url = "https://api.example.com/upload",
    body = NetworkBody.Stream(
        NetworkByteStream.fromChunks(contentLength = fileSize) { sink ->
            sink.write(firstChunk)
            sink.write(secondChunk)
        }
    ),
    progress = NetworkProgressCallbacks(
        uploadProgress = { progress ->
            println("${progress.bytesTransferred}/${progress.bytesTotal}")
        },
        downloadProgress = { progress ->
            println("downloaded ${progress.bytesTransferred}")
        }
    )
)
```

The built-in `VBTransportNetworkEngine` reports upload progress while materializing request bodies and
download progress when buffered responses are available. It does not yet stream bytes directly into the
platform transport.

## Describe stream and file bodies

Use `NetworkBody.Stream` when shared code can provide chunks. Use `NetworkBody.FileRef` when platform or
app code owns the file path and can provide bytes or a stream:

```kotlin
val body = NetworkBody.FileRef(
    path = filePath,
    contentType = "application/octet-stream",
    contentLength = fileSize,
    openStreamBlock = { path ->
        openPlatformFileStream(path)
    },
    cancelBlock = {
        cancelPlatformRead()
    }
)
```

The built-in transports now stream `Stream`, `FileRef`, and multipart bodies that contain streaming
parts on Android, iOS, and OHOS. A custom engine can keep the default buffered fallback until it
implements the corresponding capability, without changing request call sites.

## Check engine capabilities

Each engine declares what it can do through `NetworkEngine.capabilities`:

| Capability | `VBTransportNetworkEngine` |
| --- | --- |
| Request body streaming | `true` |
| Response body streaming | `true` |
| Multipart streaming | `true` |
| Upload progress callback | `true` |
| Download progress callback | `true` |

Use this when an app needs to decide whether a large upload can be sent as a true stream or must use the
buffered fallback.

## Select the transport engine with gray rollout and rollback

`NetworkClient` routes through a typed `NetworkTransportEngine` selector. Map a remote raw value once
at the app/config boundary; do not pass `"ktor"` or `"curl"` through business request code:

```kotlin
val client = NetworkClient(
    NetworkClientConfig(
        engineSelector = { request ->
            val rollout = currentNetworkRollout(request.metadata["accountId"])
            NetworkEngineSelection.fromExternalConfig(
                engine = rollout.engine, // "ktor" or "curl", parsed here only
                curlEnabled = rollout.curlEnabledOnThisPlatform,
                forcePlatformDefault = rollout.rollback
            )
        },
        engineDiagnostics = object : NetworkEngineDiagnosticsListener {
            override fun onEngineCompleted(diagnostics: NetworkEngineExecutionDiagnostics) {
                recordNetworkEngine(
                    selected = diagnostics.selection.selectedEngine,
                    reason = diagnostics.selection.reason,
                    timing = diagnostics.timing
                )
            }
        }
    )
)
```

The selector runs for each new `NetworkCall`, so a remote `rollback` change takes effect on the next
call without rebuilding the client. The decision is latched for that call: auth/policy retries never
switch engines midway. Resolution always fails closed to the platform's current default: Ktor on
Android/iOS and curl on OHOS. Android and iOS ship production curl delegates, but they are never
selected unless the typed selector explicitly requests curl and the platform delegate is available.

Every curl delegate requires one process-wide, app-owned runtime configuration. Stage the pinned bundle
with `scripts/prepare-app-owned-ca-bundle.sh`, package/copy it through the app, then install its absolute
runtime path and pinned SHA-256 before curl rollout is enabled:

```kotlin
val status = VBTransportCurl.configure(
    NetworkCurlRuntimeConfiguration(
        trustStore = NetworkCurlTrustStore(
            path = appOwnedCaAbsolutePath,
            sha256 = NetworkCurlCaBundleManifest.SHA256
        ),
        proxy = NetworkCurlProxyConfiguration.direct()
    )
)
check(status.configured) { status.detail ?: status.failureReason.name }
```

Configuration verifies the exact file bytes before publishing them to requests. Missing, unreadable,
modified, or hash-mismatched bundles fail closed. A failed reconfiguration also clears the previous
configuration, so a bad CA rotation cannot leave stale trust material active. Update the dated URL,
version, and SHA-256 together in `ca/curl-ca-bundle.env` and `NetworkCurlCaBundleManifest`; CA rotation
is an explicit reviewed source change, never an unpinned build-time download.

Proxy behavior is also explicit because libcurl does not inherit the complete Android/iOS/OHOS proxy
and PAC contract:

- `direct()` sets an empty `CURLOPT_PROXY`, including disabling environment proxy inheritance.
- `manual(url)` accepts a fixed `http(s)` or SOCKS URL already resolved by the host and passes it through
  `CURLOPT_PROXY`; environment `no_proxy` cannot override that host decision.
- `androidSystem()` reads Android's current proxy decision for every request. Static proxy exclusions
  are applied by the default `ProxySelector`. When PAC is active, Android exposes a localhost HTTP
  forwarding proxy; curl connects to that local endpoint and Android keeps ownership of PAC download,
  per-URL evaluation, ordered fallback, and proxy-change updates. If the local port is not ready or the
  selector cannot produce one effective decision, curl is ineligible and Android falls back to Ktor.
- `pacUnresolved()` makes curl ineligible. The selector falls back to Ktor on Android/iOS; an OHOS host
  must resolve PAC to a fixed URL before using its curl platform default.

This is a libcurl boundary, not a version probe. The current curl
[FAQ](https://github.com/curl/curl/blob/master/docs/FAQ.md#does-curl-support-javascript-or-pac-automated-proxy-config)
states that libcurl cannot evaluate PAC JavaScript, and its
[proxy failover TODO](https://github.com/curl/curl/blob/master/docs/TODO.md#try-next-proxy-if-one-does-not-work)
still tracks support for a PAC-style ordered list such as `PROXY a; PROXY b; DIRECT`.
`CURLOPT_PROXY` accepts one fixed proxy; setting it again replaces the previous value.

Platform APIs can evaluate the effective proxy outside libcurl, but the result is per URL and ordered:

- Android applications can call
  [`ProxySelector.getDefault().select(uri)`](https://developer.android.com/reference/java/net/ProxySelector).
  When Android installs its
  [PAC selector](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/java/android/net/PacProxySelector.java),
  the result can contain ordered direct, HTTP, and SOCKS choices. The caller owns connection failure
  notification and trying the next entry.
- Apple CFNetwork exposes `CFNetworkCopyProxiesForURL`
  (<https://developer.apple.com/documentation/cfnetwork/cfnetworkcopyproxiesforurl(_:_:)>);
  a returned auto-configuration URL must be loaded and evaluated with the asynchronous PAC API. Its
  result is also an ordered list to try in sequence.
- OHOS exposes fixed `getDefaultHttpProxy()` data from API 10. `findProxyForUrl()` is API 20+, while the
  [official device matrix](https://github.com/openharmony/docs/blob/master/en/application-dev/reference/apis-network-kit/js-apis-net-connection.md#connectionfindproxyforurl20)
  requires newer releases for phone/tablet PAC evaluation; consumers compatible with API 12 cannot
  assume it exists.

Android `androidSystem()` avoids reimplementing these semantics by delegating the whole PAC decision to
the OS localhost proxy. A host-side PAC evaluator on platforms without that forwarding proxy still needs
a per-request resolver and ordered retry state latched to one `NetworkCall`; it must also define whether
a streaming upload can be replayed after a proxy connection failure. Passing only the first PAC result
to `manual(url)` is not full PAC support. `NetworkEngineCapabilities.pacProxy` is therefore available on
Android and remains unavailable on Apple/OHOS curl delegates.

Use `NetworkEngineRolloutConfig` for stable, reversible A/B cohorts instead of ad-hoc percentages:

```kotlin
val rollout = NetworkEngineRolloutConfig(
    curlBasisPoints = remoteCurlBasisPoints,
    curlEnabled = remoteCurlEnabled,
    forcePlatformDefault = remoteRollback,
    salt = "network-curl-v1"
)

val client = NetworkClient(
    NetworkClientConfig(
        engineSelector = { request ->
            rollout.selectionFor(checkNotNull(request.metadata["accountId"]))
        }
    )
)
```

The stable bucket and rollout inputs are available in selection diagnostics without exposing the cohort
key. `forcePlatformDefault` is the immediate rollback switch. HTTPDNS remains unavailable because there
is no SNI-safe custom resolver contract; setting `httpDnsEnabled` makes curl ineligible with
`HTTPDNS_UNSUPPORTED`.

HTTP/3 is an explicit native curl gray gate. The current Android, iOS, and OHOS curl artifacts build
curl 8.16.0 with OpenSSL 3.5.4 QUIC and nghttp3 1.17.0. Enable it only in the verified curl runtime
configuration:

```kotlin
VBTransportCurl.configure(
    NetworkCurlRuntimeConfiguration(
        trustStore = NetworkCurlTrustStore(caPath, caSha256),
        proxy = NetworkCurlProxyConfiguration.direct(),
        http3Enabled = remoteHttp3GrayEnabled
    )
)
```

`http3Enabled = false` remains the default and pins requests to h2-over-TLS with h1.1 fallback.
Explicit h3 requests use `CURL_HTTP_VERSION_3`, not `3ONLY`, so a server or network without QUIC falls
back to h2/h1.1. Default and h3 traffic use separate native connection pools; a live gray h3 connection
cannot silently upgrade a later default request. `NetworkResponse.protocol` reports the actual result
(`HTTP_3`, `HTTP_2`, and so on). Runtime eligibility is based on `CURL_VERSION_HTTP3` from the linked
artifact, not the libcurl version string; a stale artifact fails with `HTTP3_UNSUPPORTED` before native
I/O. Android and iOS still select OkHttp/Darwin by default, while OHOS remains the default curl consumer.

`NetworkEngineCapabilities` reports the same runtime truth through `httpDns` and `http3`.

`NetworkEngineSelectionDiagnostics.capabilities` always describes the engine that was actually
selected, not the requested engine.

## Recover a stalled reused Android HTTP/2 connection

Android's default Ktor-OkHttp transport can optionally detect a reused h2 connection that accepted a
request but produced no response headers. The policy is default-off and should be enabled through a
stable remote-config cohort:

```kotlin
VBTransportAndroidEngine.reusedHttp2Recovery = VBTransportReusedHttp2Recovery(
    enabled = remoteStaleH2RecoveryEnabled,
    clientShardCount = 5,
    responseHeadersWatchdogMillis = 7_000,
    minimumConcurrentStalledRequests = 2,
    pingIntervalMillis = 3_000 // Alpha first-line liveness check
)
```

The five slots are independent OkHttp clients and connection pools, selected round-robin for the same
origin. This bounds a single bad reused connection's normal blast radius instead of putting the entire
foreground burst on one pool. The watchdog starts only after request headers/body have been sent. It
requires both `h2`, a reused connection, and at least two concurrently sent calls on the same physical
OkHttp connection with no response headers. A single legitimately slow endpoint therefore does not
retire a healthy pool. Once the connection-level condition is met, all waiting GET/HEAD calls registered
on that connection are cancelled for their one sequential retry, and only the affected slot's generation
is atomically drained.
The replacement is a newly constructed OkHttp client and connection pool; the other slots do not change.
The old generation closes only after its in-flight calls finish, so POST/PUT/PATCH/upload calls are never
cancelled or replayed. Bodyless GET/HEAD may make one sequential retry on a different slot within the original
total timeout; this is not a hedge, so an old completion cannot race and overwrite the fresh result.
At most `clientShardCount` replacement clients may be created for one origin in a rolling 30-second
window. If several freshly created slots also stall, the churn breaker suppresses further replacement
until the window expires instead of creating clients without bound.

`pingIntervalMillis` can detect a connection that also stopped answering PING, but it does not replace
the response-headers watchdog. Inspect `VBTransportElapseStatistics.staleH2Detected`,
`connectionOrigin`, `connectionShard`, `connectionGeneration`, `connectionIdentity`, `connectionDraining`,
`connectionRolloverRateLimited`, `freshRetry`,
`freshRetryResult`, `noResponseHeadersDurationMs`, and `staleH2ConcurrentRequestCount` during rollout.
Changing or disabling the recovery configuration publishes a new monotonic epoch: old in-flight calls
may finish, but cannot recreate or reset the new pool configuration, and idle recovery pools drain.

## Handle stable error kinds

Use `NetworkError.kind` for business/UI branching and keep raw codes/messages for diagnostics:

```kotlin
when (response.error?.kind) {
    NetworkErrorKind.CANCELLED -> return
    NetworkErrorKind.TIMEOUT -> showRetry()
    NetworkErrorKind.DNS,
    NetworkErrorKind.CONNECT,
    NetworkErrorKind.TLS -> showNetworkUnavailable()
    NetworkErrorKind.AUTH -> requestLogin()
    NetworkErrorKind.HTTP_STATUS -> showServerError(response.statusCode)
    NetworkErrorKind.DECODE -> showDataError()
    NetworkErrorKind.UNKNOWN,
    null -> showUnknownError()
}
```

Current stable buckets are `CANCELLED`, `TIMEOUT`, `DNS`, `CONNECT`, `TLS`, `HTTP_STATUS`, `DECODE`,
`AUTH`, and `UNKNOWN`.
