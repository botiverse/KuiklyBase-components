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

The iOS curl delegate requires an app-owned CA bundle. Configure its absolute path during app startup
before enabling curl in remote rollout state; a missing or blank path leaves curl unavailable and the
selector fails closed to Ktor Darwin:

```kotlin
import com.tencent.kmm.network.export.VBTransportIosCurl
import platform.Foundation.NSBundle

VBTransportIosCurl.caInfoPath = NSBundle.mainBundle.pathForResource(
    name = "cacert",
    ofType = "pem"
)
```

This Phase 2 delegate deliberately does not fall back to libcurl's compiled trust path. System trust
bridging (`SecTrust`), proxy/PAC parity, and pinning are separate platform work.
`NetworkEngineSelectionDiagnostics.capabilities` always describes the engine that was actually
selected, not the requested engine.

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
