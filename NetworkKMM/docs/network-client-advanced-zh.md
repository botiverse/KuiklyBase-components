# NetworkClient 进阶能力

这篇文档说明 shared library 或 app shell 在一次普通请求之外常用的能力。

## 接入 auth 和 token refresh

`NetworkAuthConfig` 会注入当前 token，并且对匹配 401 类响应的并发 refresh 做去重。NetworkKMM
只提供 hook，不内置任何业务 token 存储语义。

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

如果 request 已经显式设置了 auth header，`NetworkClient` 不会覆盖它。

## 使用有序 interceptor 包裹请求

需要在每次 engine attempt 前后做稳定顺序的逻辑时，用 interceptor：

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

Interceptors 按列表顺序进入 engine，`proceed` 返回后按反向顺序退出。简单的请求字段改写或响应观察，
仍然可以继续用 `requestMiddlewares` / `responseMiddlewares`。

## 观察上传和下载进度

`NetworkRequest.progress` 支持上传和下载进度回调：

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

内置 `VBTransportNetworkEngine` 会在 materialize request body 时上报上传进度，并在 buffered response
可用时上报下载进度。它目前还不会把 bytes 直接流式写入 platform transport。

## 描述 stream 和 file body

shared 代码能按 chunk 提供数据时，用 `NetworkBody.Stream`。文件由 platform 或业务侧持有时，用
`NetworkBody.FileRef`：

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

当前 Android、iOS、OHOS 的内置 transport 都会真实流式发送 `Stream`、`FileRef`，以及包含流式
part 的 multipart。自定义 engine 在实现对应 capability 之前仍可使用默认 buffered fallback，
请求调用方不需要修改 API。

## 检查 engine capability

每个 engine 通过 `NetworkEngine.capabilities` 声明能力：

| Capability | `VBTransportNetworkEngine` |
| --- | --- |
| Request body streaming | `true` |
| Response body streaming | `true` |
| Multipart streaming | `true` |
| Upload progress callback | `true` |
| Download progress callback | `true` |

App 需要判断大文件上传是否可以真 streaming、还是必须走 buffered fallback 时，读取这个 capability。

## 使用可灰度、可回滚的 transport engine selector

`NetworkClient` 在 `NetworkEngine` 边界使用 typed `NetworkTransportEngine` selector。远端配置里的
原始字符串只在 App/config 边界映射一次，不要让 `"ktor"` / `"curl"` 判断进入业务请求代码：

```kotlin
val client = NetworkClient(
    NetworkClientConfig(
        engineSelector = { request ->
            val rollout = currentNetworkRollout(request.metadata["accountId"])
            NetworkEngineSelection.fromExternalConfig(
                engine = rollout.engine, // 只在这里解析 "ktor" / "curl"
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

selector 会在每个新 `NetworkCall` 执行，因此远端把 `rollback` 翻开后无需重建 client，下一次
call 就会回退。选择结果会在当前 call 内锁定，auth/policy retry 不会中途切换 engine。解析失败、
平台禁用或实现尚未注册时，一律回退到当前平台默认：Android/iOS 为 Ktor，OHOS 为 curl。
Android/iOS 已提供 production curl delegate，但只有 typed selector 显式请求 curl 且平台 delegate
可用时才会选中。

所有 curl delegate 都要求 App 安装同一份进程级 runtime 配置。先用
`scripts/prepare-app-owned-ca-bundle.sh` 下载并校验固定版本的 CA bundle，再由 App 打包/复制，
最后在启用 curl 灰度前传入运行时绝对路径和固定 SHA-256：

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

配置发布前会校验文件的真实字节。文件缺失、不可读、被修改或 hash 不匹配都会 fail-closed；
失败的重新配置还会清掉旧配置，避免错误 CA 轮换继续沿用旧信任材料。CA 更新必须同时修改
`ca/curl-ca-bundle.env` 和 `NetworkCurlCaBundleManifest` 中的日期 URL、版本和 SHA-256，并作为
显式 source change 评审，不能在构建时无校验地下载 latest 文件。

libcurl 不会自动继承 Android/iOS/OHOS 完整的系统 proxy/PAC 合同，因此 proxy 决策也必须显式：

- `direct()` 通过空 `CURLOPT_PROXY` 关闭代理，同时禁止继承环境代理。
- `manual(url)` 接收 App/平台已经解析好的固定 `http(s)` 或 SOCKS URL；环境 `no_proxy` 不能覆盖该决策。
- `androidSystem()` 会为每个请求读取 Android 当前代理决策。静态代理排除规则由默认 `ProxySelector`
  处理；PAC 生效时，Android 会提供 localhost HTTP 转发代理，curl 只连接这个本地端点，PAC 下载、按 URL
  执行、有序 fallback 和代理变更仍由系统负责。本地端口未就绪或 selector 无法给出单个有效决策时，
  curl 变为 ineligible，Android 回退 Ktor。
- `pacUnresolved()` 会让 curl 变为 ineligible。Android/iOS selector 会回退 Ktor；OHOS 以 curl 为
  平台默认，host 必须先把 PAC 解析成固定 URL。

这是 libcurl 的能力边界，不是版本探测问题。curl 当前
[FAQ](https://github.com/curl/curl/blob/master/docs/FAQ.md#does-curl-support-javascript-or-pac-automated-proxy-config)
明确说明 libcurl 无法执行 PAC JavaScript；官方
[proxy failover TODO](https://github.com/curl/curl/blob/master/docs/TODO.md#try-next-proxy-if-one-does-not-work)
仍在跟踪 `PROXY a; PROXY b; DIRECT` 这类 PAC 有序代理链的失败切换。`CURLOPT_PROXY` 只接收一个
固定代理，再次设置只会覆盖旧值。

平台 API 可以在 libcurl 外计算有效代理，但结果是按 URL 解析的有序列表：

- Android App 可调用
  [`ProxySelector.getDefault().select(uri)`](https://developer.android.com/reference/java/net/ProxySelector)。
  系统安装
  [PAC selector](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/java/android/net/PacProxySelector.java)
  后，返回值可能包含有序的直连、HTTP 和 SOCKS 选项；调用方负责上报连接失败并继续尝试下一项。
- Apple CFNetwork 提供 `CFNetworkCopyProxiesForURL`
  （<https://developer.apple.com/documentation/cfnetwork/cfnetworkcopyproxiesforurl(_:_:)>）；
  如果结果是自动配置 URL，还要异步下载并执行 PAC，最终结果同样是需要按顺序尝试的列表。
- OHOS 从 API 10 起可读取固定 `getDefaultHttpProxy()`；`findProxyForUrl()` 是 API 20+，且官方设备矩阵
  [要求](https://github.com/openharmony/docs/blob/master/zh-cn/application-dev/reference/apis-network-kit/js-apis-net-connection.md#connectionfindproxyforurl20)
  手机/平板 PAC 执行使用更高版本。兼容 API 12 的消费端不能假定该接口存在。

Android `androidSystem()` 通过把完整 PAC 决策交给 OS localhost proxy，避免在库内重写这些语义。没有
这种本地转发代理的平台，如果由 host 自行执行 PAC，仍需按请求调用 resolver，把有序重试状态锁定在
同一个 `NetworkCall`，并明确流式上传在代理连接失败后是否可重放。只把 PAC 第一项传给 `manual(url)`
不算完整支持。`NetworkEngineCapabilities.pacProxy` 因此在 Android 可用，在 Apple/OHOS curl delegate
上仍不可用。

A/B 灰度使用稳定、可回滚的 `NetworkEngineRolloutConfig`，不要在业务层各自实现随机百分比：

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

稳定 bucket 和灰度参数会进入 diagnostics，但不会暴露 cohort key；`forcePlatformDefault` 是立即回滚
开关。HTTPDNS 仍不可用，因为当前没有保留原始 host/SNI 的安全 resolver 合同；设置
`httpDnsEnabled` 会以 `HTTPDNS_UNSUPPORTED` 使 curl ineligible。

HTTP/3 是 native curl 的显式灰度 gate。当前 Android、iOS、OHOS curl 产物使用 curl 8.16.0、
OpenSSL 3.5.4 QUIC 和 nghttp3 1.17.0；已有调用方可以继续用已验证 curl runtime 配置作为进程默认值：

```kotlin
VBTransportCurl.configure(
    NetworkCurlRuntimeConfiguration(
        trustStore = NetworkCurlTrustStore(caPath, caSha256),
        proxy = NetworkCurlProxyConfiguration.direct(),
        http3Enabled = remoteHttp3GrayEnabled
    )
)
```

运行时 Settings 开关应优先写入请求自身，避免已复制或进行中的请求观察到后续全局切换：

```kotlin
val native = VBTransportCurl.nativeStatus
val request = NetworkRequest(url = endpoint)
    .setCurlHttp3Enabled(settingsHttp3Enabled)
```

该 override 支持 OHOS platform-default trust，并优先于
`NetworkCurlRuntimeConfiguration.http3Enabled`；它本身不会选择 curl。
`native.linked`、`native.http3FeatureAvailable`、
`NetworkEngineExecutionDiagnostics.http3Requested`、`NetworkResponse.protocol`
分别表示产物/ABI 存在、编译 H3 能力、单次请求意图、真实协商协议，不能互相替代。

`http3Enabled = false` 仍是默认值，请求固定走 TLS 上的 h2，并保留 h1.1 回退。显式 h3 请求使用
`CURL_HTTP_VERSION_3` 而不是 `3ONLY`，因此服务端或网络不支持 QUIC 时会无感回退 h2/h1.1。
默认流量与 h3 灰度流量使用独立 native 连接池，已有 h3 连接不能通过复用把后续默认请求静默升级。
`NetworkResponse.protocol` 返回真实协商结果（`HTTP_3`、`HTTP_2` 等）。runtime eligibility 读取链接
产物的 `CURL_VERSION_HTTP3` feature bit，而不是只看 libcurl 版本号；旧产物会在 native I/O 前以
`HTTP3_UNSUPPORTED` fail closed。Android/iOS 默认 engine 仍分别是 OkHttp/Darwin，OHOS 仍默认消费 curl。
Android 真正选择 curl 还必须显式依赖同版本
`com.tencent.kuiklybase:network-android-curl-runtime`；普通 NetworkKMM Android AAR 仍不含任何 `.so`。

`NetworkEngineCapabilities.httpDns/http3` 会报告同一 runtime 事实。

`NetworkEngineSelectionDiagnostics.capabilities` 描述的是**实际选中的 engine**，不是请求但未命中的 engine。
宿主如需把选择结果关联到自己的配置快照，可写入
`NetworkEngineSelection.hostSelectionTag`；diagnostics 只原样回传，routing 不解释也不参与选择。

## 恢复 Android 已复用但无响应头进展的 HTTP/2 连接

Android 默认 Ktor-OkHttp transport 可选检测“请求已经发出，但复用 h2 连接一直没有响应头”的状态。
策略默认关闭，应通过稳定 remote-config cohort 灰度：

```kotlin
VBTransportAndroidEngine.reusedHttp2Recovery = VBTransportReusedHttp2Recovery(
    enabled = remoteStaleH2RecoveryEnabled,
    clientShardCount = 5,
    responseHeadersWatchdogMillis = 7_000,
    minimumConcurrentStalledRequests = 2,
    pingIntervalMillis = 3_000 // Alpha 第一层存活探测
)
```

同一 origin 的 5 个槽位分别持有独立 OkHttp client 与 ConnectionPool，请求轮询分配；一条坏复用连接
只会影响其中一份，而不是把整批前台请求压在同一个 pool。watchdog 只从 request headers/body 发送完毕
后开始计时，并且要求 `h2 + reused connection + 同一物理 OkHttp connection 至少 2 个并发请求都已发完且
没有 response headers` 同时成立；单个合法慢 endpoint 不会误退休健康 pool。连接级条件命中后，该连接上
所有等待中的 GET/HEAD 都会取消并进入各自唯一一次串行 retry。只把对应槽位的 generation 标记为
draining，并新建该槽位的 OkHttp client/ConnectionPool；其他 4 个槽位不变。旧 generation 等所有
在途调用自然结束后才关闭，因此 POST/PUT/PATCH/上传不会被取消或自动重放。无 body 的 GET/HEAD 在原 total timeout
剩余预算内最多到另一个槽位串行 fresh retry 一次；它不是 hedge，所以旧 completion 不会与新结果竞争覆盖。
同一 origin 的 30 秒滚动窗口内最多创建 `clientShardCount` 个 replacement client；如果新建的多个槽位也
继续卡住，churn breaker 会暂时抑制继续 replacement，而不是无上限创建 client，窗口过后再允许自愈。

`pingIntervalMillis` 能发现连 PING 都不再应答的连接，但不能替代 response-headers watchdog。灰度时检查
`VBTransportElapseStatistics.staleH2Detected`、`connectionOrigin`、`connectionShard`、`connectionGeneration`、
`connectionIdentity`、`connectionDraining`、`connectionRolloverRateLimited`、`freshRetry`、`freshRetryResult` 和
`noResponseHeadersDurationMs` 和 `staleH2ConcurrentRequestCount`。
修改或关闭 recovery 配置会发布新的单调 epoch：旧在途请求可以自然结束，但不能重新创建或回滚新配置的
连接池；空闲 recovery pool 会立即 drain。

## 处理稳定错误分类

业务/UI 层用 `NetworkError.kind` 分支，raw code/message 保留给 diagnostics：

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

当前稳定分类包括 `CANCELLED`、`TIMEOUT`、`DNS`、`CONNECT`、`TLS`、`HTTP_STATUS`、`DECODE`、
`AUTH` 和 `UNKNOWN`。
