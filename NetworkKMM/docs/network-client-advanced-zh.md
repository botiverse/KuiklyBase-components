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

iOS curl delegate 必须由 app 提供 CA bundle。启用远程 curl 灰度前，在 app 启动阶段配置绝对路径；
路径缺失或为空时 curl 保持 unavailable，selector 会 fail-closed 到 Ktor Darwin：

```kotlin
import com.tencent.kmm.network.export.VBTransportIosCurl
import platform.Foundation.NSBundle

VBTransportIosCurl.caInfoPath = NSBundle.mainBundle.pathForResource(
    name = "cacert",
    ofType = "pem"
)
```

Phase 2 delegate 不会回退到 libcurl 编译时默认信任路径。系统信任桥接（`SecTrust`）、proxy/PAC
对齐和 pinning 属于后续平台工作。`NetworkEngineSelectionDiagnostics.capabilities` 描述的是
**实际选中的 engine**，不是请求但未命中的 engine。

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
