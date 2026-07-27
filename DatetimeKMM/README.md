English | [简体中文](README-zh.md)

# KuiklyBase Datetime

KuiklyBase Datetime is a deliberately small Kotlin Multiplatform foundation
for wall-clock time and the device's current timezone. It targets Android,
iOS, and OHOS without adding the full `kotlinx-datetime` ABI or timezone
database to a Kuikly application.

The candidate Maven coordinates are:

- normal Android/iOS tree: `com.tencent.kuiklybase:datetime:<version>`;
- OHOS K/N 2.0.21 tree: `com.tencent.kuiklybase:datetime:<version>-ohos`.

`0.1.0-raft.0` is reserved in source as the first candidate. Treat it as
unreleased until an authorized GitHub Packages workflow has verified that the
immutable coordinate is free and has published all platform variants.

## API

```kotlin
import com.tencent.kuiklybase.datetime.Clock
import com.tencent.kuiklybase.datetime.Instant
import com.tencent.kuiklybase.datetime.SystemTimeZone

val now: Instant = Clock.System.now()
val epochMillis: Long = now.toEpochMilliseconds()

val observation = SystemTimeZone.snapshot(now)
println("${observation.zoneId}: ${observation.offset.totalSeconds}")

// An observation is immutable. Re-read after an OS timezone-change signal.
if (!SystemTimeZone.isCurrent(observation)) {
    val refreshed = SystemTimeZone.snapshot(now)
}
```

`Clock.System` is a wall clock, not a monotonic duration source. Business
logic should accept a `Clock` parameter so tests can inject a fixed value.
`Instant` has millisecond precision and intentionally provides no parsing,
calendar, or serialization API.

`SystemTimeZone.snapshot(instant)` reads the current system zone every time and
evaluates its UTC offset at exactly that epoch. It does not cache a zone or an
offset. `SystemTimeZone.isCurrent(old)` re-evaluates the old epoch and is the
explicit invalidation probe for zone-ID or rule/offset changes.

## Build

Normal metadata and Android tests:

```bash
./gradlew :datetime:compileCommonMainKotlinMetadata \
  :datetime:testDebugUnitTest --no-daemon
```

iOS compilation/tests require macOS:

```bash
./gradlew :datetime:compileKotlinIosArm64 \
  :datetime:iosSimulatorArm64Test --no-daemon
```

OHOS uses the separate KBA tree and the HarmonyOS SDK image documented by the
repository:

```bash
export OHOS_SDK_HOME="$OHOS_BASE_SDK_HOME"
./gradlew -c settings.ohos.gradle.kts \
  :datetime:compileKotlinOhosArm64 --no-daemon
```

See [MOBILE_MIGRATION.md](MOBILE_MIGRATION.md) for the separate consumer
rollout and [PROVENANCE.md](legal/META-INF/PROVENANCE.md) for license,
upstream, and divergence evidence.
