# GitHub Packages and Raft Artifacts Publishing

Every NetworkKMM release is published under the same Maven GAVs to both repositories:

```text
https://maven.pkg.github.com/bytemain/KuiklyBase-components
https://maven.artifacts.botiverse.dev
```

Raft Artifacts is additive: it does not rename the group, artifact, or version,
and it does not remove GitHub Packages. The Raft authorization scope is the
original Maven group `com.tencent.kuiklybase`.

The publishing flow covers all current Kotlin Multiplatform publications:

| Platform | Gradle publication |
| --- | --- |
| Common metadata | `kotlinMultiplatform` |
| Android | `android` |
| iOS simulator x64 | `iosX64` |
| iOS device arm64 | `iosArm64` |
| iOS simulator arm64 | `iosSimulatorArm64` |
| HarmonyOS arm64 | `ohosArm64` |

The verified Maven artifact IDs are:

```text
com.tencent.kuiklybase:network
com.tencent.kuiklybase:network-android
com.tencent.kuiklybase:network-android-curl-runtime
com.tencent.kuiklybase:network-iosx64
com.tencent.kuiklybase:network-iosarm64
com.tencent.kuiklybase:network-iossimulatorarm64
com.tencent.kuiklybase:network-ohosarm64
com.tencent.kuiklybase:network-ohos-runtime
com.tencent.kuiklybase:network-ohos-runtime-gradle-plugin
```

`network-android` publishes both release and debug AAR variants because the module currently calls `publishLibraryVariants("release", "debug")`. `network-ohosarm64` also publishes the generated cinterop KLIB.
`network-android-curl-runtime` is a payload-only, opt-in release AAR containing exactly:

```text
jni/arm64-v8a/libnetworkkmmcurl.so
jni/x86_64/libnetworkkmmcurl.so
```

The normal `network-android` AAR intentionally remains zero-so.
`network-ohos-runtime` is a zip artifact that contains the HarmonyOS runtime libraries:

```text
arm64-v8a/libpbcurlwrapper.so
arm64-v8a/libopenssl.so
arm64-v8a/libc++_shared.so
```

Consumers should depend on the root artifact and let Gradle metadata select the platform artifact:

```kotlin
implementation("com.tencent.kuiklybase:network:0.1.0-raft.0")
```

Android apps that intentionally select the curl engine must also add the
matching native runtime version explicitly:

```kotlin
implementation("com.tencent.kuiklybase:network-android-curl-runtime:0.1.0-raft.28")
```

Keep both versions identical. Omitting this coordinate preserves the existing
OkHttp default and makes `VBTransportCurl.nativeStatus.linked` false.

HarmonyOS apps still need these native runtime libraries in the app entry module. The Maven/KLIB publication provides the Kotlin artifact; the Gradle plugin resolves `network-ohos-runtime` and copies the `.so` files into `entry/libs/arm64-v8a/`.

## Consume From Raft Artifacts

The `com.tencent.kuiklybase` scope is public, so consumers need no credential:

```kotlin
repositories {
    maven {
        name = "raftArtifacts"
        url = uri("https://maven.artifacts.botiverse.dev")
    }
}
```

Add the same repository under `pluginManagement.repositories` when resolving
`com.tencent.kuiklybase.network.ohos-runtime`. Coordinates and versions are
identical to GitHub Packages, so switching repository order does not require a
dependency rewrite.

## Consume From GitHub Packages

GitHub Packages requires authentication for package reads and writes, including public packages. For local builds, copy [`github-packages.properties.example`](./github-packages.properties.example) to `github-packages.properties` in the consuming repository root:

```properties
githubPackagesUsername=your-github-user
githubPackagesToken=ghp_xxx
```

Add the repository to the consuming Gradle build. Gradle reads `github-packages.properties` first and falls back to environment variables when the file does not exist:

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
}
```

For local consumption, use a classic GitHub PAT with `read:packages`:

```bash
export GITHUB_ACTOR=your-github-user
export GITHUB_PACKAGES_TOKEN=ghp_xxx
```

For GitHub Actions in a repository that has read access to the package, `GITHUB_TOKEN` can be used.

## Consume OHOS Runtime

Add the GitHub Packages Maven repository to both plugin resolution and dependency resolution:

```kotlin
// settings.gradle.kts
import java.util.Properties

pluginManagement {
    val githubPackagesProperties = Properties().apply {
        val propertiesFile = settingsDir.resolve("github-packages.properties")
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
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    val githubPackagesProperties = Properties().apply {
        val propertiesFile = settingsDir.resolve("github-packages.properties")
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
        google()
        mavenCentral()
    }
}
```

Apply the plugin from the Gradle project that owns the OHOS app directory:

```kotlin
plugins {
    id("com.tencent.kuiklybase.network.ohos-runtime") version "0.1.0-raft.0"
}

networkOhosRuntime {
    // Default is ohosApp/entry/libs/arm64-v8a if ohosApp/entry exists,
    // otherwise entry/libs/arm64-v8a.
    outputDir.set(layout.projectDirectory.dir("ohosApp/entry/libs/arm64-v8a"))
}
```

Then sync the native runtime before building the OHOS app:

```bash
./gradlew copyNetworkOhosRuntimeLibs
```

The plugin version is also used as the default `network-ohos-runtime` artifact version. Override only if needed:

```kotlin
networkOhosRuntime {
    version.set("0.1.0-raft.0")
}
```

## Manual Dual Publish

Use a classic GitHub PAT with `write:packages` and a Raft Maven token restricted
to the `com.tencent.kuiklybase` scope. Do not place either token on the command
line:

```bash
export GITHUB_PACKAGES_USERNAME=bytemain
export GITHUB_PACKAGES_TOKEN=ghp_xxx
export RAFT_ARTIFACTS_PUBLISH_TOKEN=raft_xxx
export MAVEN_VERSION=0.1.0-raft.0

cd NetworkKMM
./scripts/publish-github-packages.sh
```

The entrypoint rejects a dirty checkout, resolves provenance itself with
`git rev-parse HEAD`, rejects SNAPSHOT and `-ohos`-suffixed base versions, and
fails before Gradle if either destination credential is absent. It publishes
each selected publication to both the existing `GithubPackagesRepository` and
the derived `RaftArtifactsRepository` task. Repository tokens stay in inherited
environment variables rather than Gradle process arguments.

By default the script pairs these existing GitHub publication tasks with their
Raft equivalents:

```text
:network:publishAndroidPublicationToGithubPackagesRepository
:network-android-curl-runtime:publishAndroidCurlRuntimePublicationToGithubPackagesRepository
:network:publishIosX64PublicationToGithubPackagesRepository
:network:publishIosArm64PublicationToGithubPackagesRepository
:network:publishIosSimulatorArm64PublicationToGithubPackagesRepository
:network:publishOhosArm64PublicationToGithubPackagesRepository
:network-ohos-runtime:publishAllPublicationsToGithubPackagesRepository
:network-ohos-runtime-gradle-plugin:publishAllPublicationsToGithubPackagesRepository
:network:publishKotlinMultiplatformPublicationToGithubPackagesRepository
```

The script checks which publish tasks exist on the current host before invoking Gradle. On Linux/HarmonyOS hosts, Kotlin/Native does not create iOS publish tasks, so the script skips them unless `NETWORK_REQUIRE_TASKS=true` is set. Use `NETWORK_DRY_RUN=true` to validate task selection without uploading artifacts.

Before publishing the Android curl runtime, the script assembles both Android
AARs and runs `scripts/verify-android-curl-runtime-aar.sh`. The gate proves the
normal AAR has zero native libraries, the runtime AAR has only the two expected
ABIs, and each packaged byte stream equals the committed source `.so`.

The root `kotlinMultiplatform` metadata publication is intentionally last in the default task list. If a target publication, runtime artifact, or plugin publication fails, consumers will not see new metadata that points at incomplete artifacts.

The release workflow probes every publication's primary file, POM, Gradle
metadata where applicable, and sources artifact in both repositories. A whole
publication may be retried only when all its required files are absent. A mixed
immutable coordinate (some files present, some absent) fails closed instead of
attempting an overwrite. `NETWORK_PUBLISH_TASKS` is an exact allowlist for an
explicit retry and must equal all missing publications in that lane; it cannot
silently omit work:

```bash
NETWORK_PUBLISH_TASKS=":network:publishAndroidPublicationToGithubPackagesRepository :network:publishOhosArm64PublicationToGithubPackagesRepository" \
  ./scripts/publish-github-packages.sh
```

To require every requested task to exist on the current host:

```bash
NETWORK_REQUIRE_TASKS=true ./scripts/publish-github-packages.sh
```

## CI Dual Publish

The workflow is `.github/workflows/publish-network-github-packages.yml`.

It can be triggered in two ways:

```bash
git tag network-v0.1.0-raft.0
git push origin network-v0.1.0-raft.0
```

The tag version must match `mavenVersion` in `NetworkKMM/gradle.properties`; CI fails early if they differ.

Or run **Publish NetworkKMM to GitHub Packages and Raft Artifacts** from GitHub Actions and optionally pass `version`.

The workflow splits publishing by host:

- The Linux jobs use `ghcr.io/bytemain/harmony-next-pipeline-docker/harmonyos-ci-image:v6.1.1.280`, matching the HarmonyOS command-line tools used by `bytemain/soduku-harmony`. They publish Android (including the separately probed curl runtime), OHOS, OHOS runtime, and the Gradle plugin.
- The Linux job maps the image-provided `OHOS_BASE_SDK_HOME` to `OHOS_SDK_HOME`, `OHOS_NDK_HOME`, `OHOS_LLVM_HOME`, and DevEco SDK variables so Kotlin/Native can find the HarmonyOS sysroot during `ohosArm64` cinterop.
- The macOS iOS job runs in parallel with the Linux job and publishes the iOS KLIB artifacts.
- The macOS metadata job runs after the platform jobs succeed and publishes the root `kotlinMultiplatform` metadata publication.
- All publish jobs install Android SDK platform 33 and build-tools 33.0.2 because the Android Gradle plugin is configured during project evaluation.
- Every publication job binds the protected GitHub Environment
  `raft-artifacts-production`. GitHub Packages uses the job-scoped
  `GITHUB_TOKEN`; Raft uses the environment secret
  `RAFT_ARTIFACTS_PUBLISH_TOKEN`, whose token is restricted to
  `com.tencent.kuiklybase`.
- Each job verifies both repositories after upload. The terminal job also fails
  if any lane failed or every lane was already complete, preserving immutable
  version semantics.
- Every generated Maven POM records the exact clean-checkout SHA as both
  `dev.raft.sourceSha` and the SCM tag. `${{ github.sha }}` is deliberately not
  a provenance input.

## Host Notes

Android publication requires an Android SDK. OHOS publication requires the HarmonyOS command-line/native toolchain from the CI image.

iOS here is published as Kotlin Multiplatform KLIB artifacts, not as the CocoaPods/XCFramework demo artifact. The iOS publish tasks are host-specific and should run on macOS. The root KMP metadata publication should run only after Android, OHOS, runtime, plugin, and iOS artifacts are available for that version.
