[TOC]

## KMM Network

[English Documentation](./README.md)

### 简介

本项目基于Kotlin Multiplatform技术，构建了一套跨平台网络请求(http get/post 等)解决方案,支持Android、iOS及HarmonyOS三大移动端平台.
HarmonyOS 端默认使用 libcurl；Android/iOS 端仍以 Ktor 为平台默认引擎，同时提供生产 libcurl delegate，可通过 `NetworkEngineSelection` 显式灰度和回滚。

### 接入

#### Kotlin 接入
```kotlin
import java.util.Properties

val githubPackagesProperties = Properties().apply {
    val propertiesFile = rootProject.file("github-packages.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use(::load)
    }
}

repositories {
    maven {
        name = "bytemainKuiklyBase"
        url = uri("https://maven.pkg.github.com/bytemain/KuiklyBase-components")
        credentials {
            username = githubPackagesProperties.getProperty("githubPackagesUsername")
                ?: githubPackagesProperties.getProperty("gpr.user")
                ?: System.getenv("GITHUB_ACTOR")
            password = githubPackagesProperties.getProperty("githubPackagesToken")
                ?: githubPackagesProperties.getProperty("gpr.key")
                ?: System.getenv("GITHUB_PACKAGES_TOKEN")
                ?: System.getenv("GITHUB_TOKEN")
        }
    }
    // 保留业务已有的 google()、mavenCentral()；
    // 如果 Kuikly 相关依赖来自腾讯 Maven，也继续保留腾讯 Maven。
}

// 在 build.gradle.kts / build.ohos.gradle.kts 添加依赖
implementation("com.tencent.kuiklybase:network:0.1.0-raft.0")
// 如有疑问，参考各端 demo app 接入 (androidApp/, iosApp/, ohosApp/目录下的示例)
```

#### GitHub Packages
bytemain fork 会把 Android、iOS、HarmonyOS 三端 KMP artifacts 发布到 GitHub Packages。GitHub Packages Maven 即使是 public 包也需要 credentials。本地构建时，把 [`docs/github-packages.properties.example`](./docs/github-packages.properties.example) 复制到消费仓库根目录的 `github-packages.properties`，填入带 `read:packages` 权限的 classic PAT；文件不存在时，Gradle 会回退读取 `GITHUB_ACTOR`、`GITHUB_PACKAGES_TOKEN` 或 `GITHUB_TOKEN`。手动发布、CI 发布和消费端仓库配置见 [GitHub Packages 发布文档](./docs/github-packages-publishing.md)。

#### 新请求优先使用 `NetworkClient`
新的 KMP 调用点建议把 `NetworkClient` 作为 typed request 边界，不再直接拼 `VBTransportRequest`。
文档按功能和使用方式组织：

- 创建 GET/POST 请求、设置 header/query/body、发起请求、读取响应、取消请求：
  [NetworkClient 使用指南](./docs/network-client-usage-zh.md)
- 接入 auth/header middleware、timeout/retry/priority 策略、有序 interceptor、上传/下载进度、
  stream/file body、engine capability 检查和稳定错误分类：
  [NetworkClient 进阶能力](./docs/network-client-advanced-zh.md)

`VBTransportService` 只建议继续用于历史接入点或底层 engine 封装。

#### Socket.IO（raft.30，OHOS v1）

raft.30 提供基于 C++ / libcurl WebSocket 的 Engine.IO v4 + Socket.IO v4
客户端。协议、ping/pong、auth、事件和重连都由 C++ 单 owner 管理，KMP 只暴露
薄 API：

```kotlin
val client = NetworkSocketIoFactory.create(
    NetworkSocketIoConfig(
        serverUrl = "https://example.com",
        authJson = """{"token":"..."}""",
    ),
    object : NetworkSocketIoListener {
        override fun onState(state: NetworkSocketIoState, code: Int, detail: String) = Unit
        override fun onEvent(eventName: String, payloadJson: String) = Unit
    },
)
client.start()
client.emit("room:join", """{"roomId":"room-1"}""")
client.close()
```

V1 只支持 default namespace 与 text JSON event，不支持 binary event/ack。
`NetworkSocketIoFactory.isSupported` 当前只在 OHOS 为 `true`；Android/iOS 会
显式 fail-closed，不能把它当成已完成的三端迁移。

#### 网络权限声明
##### Android
```kotlin
// 在 AndroidManifest.xml 中添加
<uses-permission android:name="android.permission.INTERNET" />
```
##### HarmonyOS
```json5
// 工程的 entry/src/main/module.json5 中添加
"requestPermissions": [
      {
        "name": "ohos.permission.INTERNET",
        "reason": "$string:internet_permission_reason",
        "usedScene": {
          "abilities": [
            "EntryAbility"
          ],
          "when": "inuse"
        }
      },
      {
        "name": "ohos.permission.GET_NETWORK_INFO",
        "reason": "$string:network_info_permission_reason",
        "usedScene": {
          "abilities": [
            "EntryAbility"
          ],
          "when": "inuse"
        }
      },
      {
        "name": "ohos.permission.GET_WIFI_INFO",
        "reason": "$string:wifi_info_permission_reason",
        "usedScene": {
          "abilities": [
            "EntryAbility"
          ],
          "when": "inuse"
        }
      }
]
```

#### HarmonyOS Native 库接入
鸿蒙端底层通过 `pbcurlwrapper` 调用 libcurl，除了 Kotlin 依赖和权限，还必须集成 native 库。bytemain fork 会把这些库发布成 `com.tencent.kuiklybase:network-ohos-runtime`，并提供 Gradle 插件复制到 OHOS entry 模块。详细步骤见 [HarmonyOS 接入文档](./docs/harmonyos-integration.md)。

必须放置以下 `.so` 文件：

| 库文件 | 作用 |
| --- | --- |
| `libpbcurlwrapper.so` | curl 网络请求封装库 |
| `libopenssl.so` | HTTPS/SSL 支持库 |
| `libc++_shared.so` | native 库依赖的 C++ runtime |

放置路径：

```text
ohosApp/entry/libs/arm64-v8a/
├── libpbcurlwrapper.so
├── libopenssl.so
└── libc++_shared.so
```

如果业务鸿蒙工程存在 native entry CMake target，还需要在 `ohosApp/entry/src/main/cpp/CMakeLists.txt` 中 `add_library(... IMPORTED)` 并在 `target_link_libraries` 中链接 `pbcurlwrapper openssl`。当前 demo 工程已在 `NetworkKMM/ohosApp/entry/libs/arm64-v8a/` 内置上述库文件。

#### 初始化
```kotlin
val logImpl = object : IVBPBLog {
    override fun d(tag: String?, content: String?) {
        print("[$tag] $content\n")
    }

    override fun i(tag: String?, content: String?) {
        print("[$tag] $content\n")
    }

    override fun e(tag: String?, content: String?, throwable: Throwable?) {
        print("[$tag] $content\n")
    }
}
val config = VBTransportInitConfig()
config.logImpl = logImpl
VBTransportInitHelper.init(config)
```

#### curl runtime 必需配置

Android/iOS 启用 curl 灰度前，以及 OHOS 通过默认 curl engine 发请求前，App 必须安装经过校验的
CA bundle，并显式给出 proxy 决策：

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

使用 `scripts/prepare-app-owned-ca-bundle.sh` 生成仓库固定版本的 Mozilla CA 文件；runtime 会拒绝
缺失或 SHA-256 不匹配的文件。Proxy 必须明确选择 `direct()`、`manual(fixedProxyUrl)`、Android 专用
`androidSystem()` 或 `pacUnresolved()`。Android 系统 PAC 会走 OS 维护的本地转发代理；其他平台 PAC
未解析时 curl 会变为 ineligible，不会静默绕过系统代理。CA 轮换、稳定
A/B 灰度、立即回滚、diagnostics 和当前 HTTPDNS/HTTP3 gate 见
[NetworkClient 进阶能力](./docs/network-client-advanced-zh.md)。

### 常用网络请求示例
在 network/src/commonMain/service/VBTransportServiceTest.kt 中包含一些常用 http get/post/string/byte/自定义 method 类型请求示例,可以参考.

#### 自定义 Method 请求
```kotlin
val request = VBTransportRequest().apply {
    method = VBTransportMethod.PUT
    url = "https://httpbin.org/put"
    header["Content-Type"] = VBTransportContentType.JSON.toString()
    header["Authorization"] = "Bearer sample-token"
    data = """{"name":"Kuikly","method":"PUT"}"""
}

VBTransportService.sendRequest(request) { response ->
    println(response.data)
}
```

#### 二进制文件上传
```kotlin
val request = VBTransportRequest().apply {
    method = VBTransportMethod.POST
    url = "https://httpbin.org/post"
    header["Content-Type"] = VBTransportContentType.BYTE.toString()
    header["X-File-Name"] = "sample.bin"
    data = fileBytes
    totalTimeout = 10000
}

VBTransportService.sendRequest(request) { response ->
    println(response.errorMessage)
}
```

#### Multipart 文件上传
```kotlin
val multipartBody = VBTransportMultipartBodyBuilder()
    .addFormField("name", "Kuikly")
    .addFormField("uploadType", "multipart")
    .addFile("file", "sample.bin", fileBytes, VBTransportContentType.BYTE.toString())
    .build()

val request = VBTransportRequest().apply {
    method = VBTransportMethod.POST
    url = "https://httpbin.org/post"
    header["Authorization"] = "Bearer sample-token"
    setMultipartBody(multipartBody)
    totalTimeout = 10000
}

VBTransportService.sendRequest(request) { response ->
    println(response.data)
}
```

### demo 工程运行使用说明
#### Android
直接 run androidApp target 即可.

#### iOS
```kotlin
1) 编译 xcframework 产物, Gradle 任务路径在: NetworkKMM/network/Tasks/coocapods/podPublishDebugXCFramework
2) 打开 iosApp/Podfile 文件, 取消下面这行注释:
pod 'network', :path => '../network/build/cocoapods/publish/debug'
3) 在 iosApp/Podfile 路径下打开终端,执行 pod install
4) Xcode 打开 iosApp.xcworkspace 工程运行即可
```

#### HarmonyOS
```kotlin
1) 编译 libkn.so 产物, Gradle 任务路径在: NetworkKMM/network-sample/build/ohosArm64Binaries
2) 编译产生的 libkn.so 产物在 network-sample 的临时 build/bin/ohosArm64_debugShared/libkn.so 文件里可以找到
3) 将该 libkn.so 拷贝到 ohosApp/entry/libs/arm64-v8a/ 目录下, 覆盖已有的 libkn.so 文件.
4) 确认 ohosApp/entry/libs/arm64-v8a/ 中存在 libkn.so、libpbcurlwrapper.so、libopenssl.so
5) 鸿蒙 IDE(DevEco-Studio) 打开 ohosApp 工程运行即可.
```

### 说明
1. 鸿蒙端目前底层网络请求使用开源库libcurl, 但没有直接调用libcurl的接口,而是在其基础上封装了一层wrapper层,供kotlin端调用.封装代码在: ohosApp/pbcurlwrapper/下.
2. 若需修改封装层的逻辑,修改完打产物时,直接在 DevEco-Studio 里,针对 pbcurlwrapper 执行 Build/Make Module 'pbcurlwrapper' 即可, 其产物在 pbcurlwrapper 目录下的临时文件夹 build/default/intermediate/cmake/default/obj/arm64-v8a/libpbcurlwrapper.so 中找到.
3. 若修改完想在 ohosApp 这个 demo 工程里运行,可以将 build 下的 libpbcurlwrapper.so 手动拷贝到 entry/libs/arm64-v8a/ 下,覆盖原有的 so 文件即可.
4. 外部 HarmonyOS 工程接入时,请按 [HarmonyOS 接入文档](./docs/harmonyos-integration.md) 同时配置权限、native 库和 CMake 链接.

### ChangeLog

[版本更新记录](./docs/changelog.md)
