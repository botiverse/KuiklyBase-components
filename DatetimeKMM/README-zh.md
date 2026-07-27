[English](README.md) | 简体中文

# KuiklyBase Datetime

KuiklyBase Datetime 是面向 Android、iOS、OHOS 的最小 Kotlin Multiplatform
时间基座，只提供系统墙上时钟和当前系统时区能力，不把完整的
`kotlinx-datetime` ABI 或时区数据库引入 Kuikly 应用。

候选 Maven 坐标：

- Android/iOS 普通构建树：`com.tencent.kuiklybase:datetime:<version>`；
- OHOS K/N 2.0.21 构建树：`com.tencent.kuiklybase:datetime:<version>-ohos`。

源码暂以 `0.1.0-raft.0` 作为首个候选版本。在经授权的 GitHub Packages
流程确认不可变坐标未占用、并发布完全部平台变体前，请勿把它视为已发布版本。

## API

```kotlin
import com.tencent.kuiklybase.datetime.Clock
import com.tencent.kuiklybase.datetime.Instant
import com.tencent.kuiklybase.datetime.SystemTimeZone

val now: Instant = Clock.System.now()
val epochMillis: Long = now.toEpochMilliseconds()

val observation = SystemTimeZone.snapshot(now)
println("${observation.zoneId}: ${observation.offset.totalSeconds}")

// observation 是不可变快照；收到系统时区变化事件后应重新读取。
if (!SystemTimeZone.isCurrent(observation)) {
    val refreshed = SystemTimeZone.snapshot(now)
}
```

`Clock.System` 是墙上时钟，不是计算耗时的单调时钟。业务逻辑应注入 `Clock`，
以便测试使用固定时间。`Instant` 只有毫秒精度，刻意不提供解析、日历和序列化 API。

`SystemTimeZone.snapshot(instant)` 每次都重新读取当前系统时区，并计算该精确
epoch 的 UTC offset，不跨调用缓存时区或 offset。`SystemTimeZone.isCurrent(old)`
会按旧快照的 epoch 主动复核当前系统配置，是 zone ID 或规则/offset 变化的明确失效信号。

## 构建

普通 metadata 与 Android 测试：

```bash
./gradlew :datetime:compileCommonMainKotlinMetadata \
  :datetime:testDebugUnitTest --no-daemon
```

iOS 编译和测试必须在 macOS 执行：

```bash
./gradlew :datetime:compileKotlinIosArm64 \
  :datetime:iosSimulatorArm64Test --no-daemon
```

OHOS 使用独立 KBA 构建树和仓库约定的 HarmonyOS SDK 镜像：

```bash
export OHOS_SDK_HOME="$OHOS_BASE_SDK_HOME"
./gradlew -c settings.ohos.gradle.kts \
  :datetime:compileKotlinOhosArm64 --no-daemon
```

消费端的独立渐进迁移见 [MOBILE_MIGRATION.md](MOBILE_MIGRATION.md)，许可证、
上游 commit 和差异证据见 [PROVENANCE.md](legal/META-INF/PROVENANCE.md)。
