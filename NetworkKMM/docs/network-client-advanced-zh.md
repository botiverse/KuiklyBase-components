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
- `pacUnresolved()` 会让 curl 变为 ineligible。Android/iOS selector 会回退 Ktor；OHOS 以 curl 为
  平台默认，host 必须先把 PAC 解析成固定 URL。

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
开关。HTTPDNS/HTTP3 当前仍是显式 gate：设置 `httpDnsEnabled` 或 `http3Enabled` 会分别以
`HTTPDNS_UNSUPPORTED` / `HTTP3_UNSUPPORTED` 使 curl ineligible。当前尚无保留原始 host/SNI 的
自定义 DNS 合同，native artifact 也没有编入 QUIC backend；`NetworkEngineCapabilities.httpDns/http3`
会报告同一事实，不能仅凭 libcurl 版本推断已支持。

`NetworkEngineSelectionDiagnostics.capabilities` 描述的是**实际选中的 engine**，不是请求但未命中的 engine。

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
