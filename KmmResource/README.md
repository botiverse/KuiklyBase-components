# KMM Multiplatform Resource Management Solution

[中文README](./README中文.md)

This project implements a cross-platform native resource management solution using **Kotlin Multiplatform (KMP)** technology, supporting Android, iOS, and HarmonyOS platforms. It generates type-safe resource access classes (e.g., `MR` class) during compilation, providing compile-time validation and unified resource management across platforms through standardized encapsulation mechanisms (Kotlin/Native klib & Kotlin/JVM aar).

## Core Concept
**What does the resource manager actually manage?**  
The KMM resource manager fundamentally exposes platform-specific resource identifiers:
- Android: Resource IDs from R class
- iOS: Bundle+name combinations
- HarmonyOS: Resource names

## Version Matrix

| Component | Stable | Beta |
|-----------|--------|------|
| com.tencent.kuiklybase.resource.generator | 0.1.0-raft.1 | 0.1.0-raft.1 |
| resource-core | 0.1.0-raft.1 | 0.1.0-raft.1 |
| resource-compose | Standard `androidx.compose` / JetBrains Compose wrapper; opt-in only | Standard `androidx.compose` / JetBrains Compose wrapper; opt-in only |
| @kuiklybase/resource_compose | 0.0.1 | 0.0.1 |

## Gradle Integration

### Maven repositories

Raft publishes `resource-core` and the `com.tencent.kuiklybase.resource.generator`
Gradle plugin to Raft Artifacts and GitHub Packages. For anonymous downloads,
add `maven("https://maven.artifacts.botiverse.dev")` to both
`pluginManagement.repositories` and `dependencyResolutionManagement.repositories`.
To use GitHub Packages, configure credentials in both places as shown below.

```kotlin
pluginManagement {
    repositories {
        maven("https://maven.pkg.github.com/botiverse/KuiklyBase-components") {
            credentials {
                username = providers.gradleProperty("githubPackagesUsername")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .orNull
                password = providers.gradleProperty("githubPackagesToken")
                    .orElse(providers.environmentVariable("GITHUB_PACKAGES_TOKEN"))
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .orNull
            }
        }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.pkg.github.com/botiverse/KuiklyBase-components") {
            credentials {
                username = providers.gradleProperty("githubPackagesUsername")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .orNull
                password = providers.gradleProperty("githubPackagesToken")
                    .orElse(providers.environmentVariable("GITHUB_PACKAGES_TOKEN"))
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .orNull
            }
        }
        google()
        mavenCentral()
    }
}
```

### KMM Project Setup

**Root `build.gradle.kts`:**
```kotlin
plugins {
    id("com.tencent.kuiklybase.resource.generator").version("0.1.0-raft.1").apply(false)
}
```

**Module `build.gradle.kts`:**
```kotlin
plugins {
    id("com.tencent.kuiklybase.resource.generator")
}

commonMain.dependencies {
    implementation("com.tencent.kuiklybase:resource-core:0.1.0-raft.1")
}

multiplatformResources {
    multiplatformResourcesPackage = "net.novate.kotlin" // Required
    multiplatformResourcesPrefix = "ohshow"             // Required
    multiplatformResourcesClassName = "MR"              // Optional (default: MR)
    iosBaseLocalizationRegion = "en"                    // Optional (default: "en") 
    multiplatformResourcesSourceSet = "commonMain"      // Optional (default: "commonMain")
    commonGeneratedDir = "build/generated/tmm-res-common" // Optional stable common MR root
    multiplatformResourcesVisibility = MRVisibility.Internal // Optional
}
```

| Parameter | Description |
|-----------|-------------|
| `multiplatformResourcesPackage` | Package name for generated MR class |
| `multiplatformResourcesPrefix` | Unique resource prefix to avoid conflicts |
| `multiplatformResourcesClassName` | Generated class name (default: `MR`) |
| `iosBaseLocalizationRegion` | Base localization region for iOS bundles |
| `multiplatformResourcesSourceSet` | Target source set for resource processing |
| `commonGeneratedDir` | Optional stable generated root for common MR sources. When set to `build/generated/tmm-res-common`, the plugin registers `build/generated/tmm-res-common/commonMain/src` in `commonMain` instead of the volatile default `build/generated/tmm-res/commonMain/src`. Platform generated outputs continue to use `build/generated/tmm-res`. |
| `multiplatformResourcesVisibility` | Visibility modifier for generated classes |

`resource-compose` is not required for plain KMP MR generation. Consumers that
already provide their own UI wrappers should depend only on `resource-core`.
The current `resource-compose` module is a standard Compose wrapper: its source
imports `androidx.compose.*` APIs and its OHOS path still uses
`org.jetbrains.compose.runtime/foundation` `1.6.1-KBA-001`, because plain
JetBrains `1.7.3` artifacts do not publish an `ohos_arm64` native variant.
The release workflow builds `resource-core` and the generator plugin once and uploads
the saved Maven files directly to GitHub Packages and Raft Artifacts. Push a
`kmmresource-v<version>` tag matching `mavenVersion`, or run the existing workflow
on `master`. Select `publish_resource_compose` to include the standard Compose wrapper.
If delivery is interrupted, rerun the failed job in that run to reuse its saved
build artifacts. The publication receipt lists the verified files and their SHA-256 hashes.

### Kuikly Compose compatibility

KuiklyBase public artifacts should align to the public Kuikly framework
artifact line. For the current public Kuikly `2.4.2` line, Android and OHOS use
different Kotlin suffixes:

| Layer | Version / coordinate |
|-------|----------------------|
| Kuikly framework base | `2.4.2` |
| Android Maven artifacts | `com.tencent.kuikly-open:*:2.4.2-2.0.21` |
| OHOS Maven/KLIB artifacts | `com.tencent.kuikly-open:*:2.4.2-2.0.21-ohos` |
| OHOS build plugin line | `2.0.21-KBA-010` |

Kuikly Compose exposes `com.tencent.kuikly.compose.*` types, while
`resource-compose` exposes `androidx.compose.*` types. Do not treat publishing
the current `resource-compose` module as Kuikly framework alignment. A
Kuikly-aligned wrapper should be introduced as a separate source/API surface, or
the existing wrapper must be intentionally migrated from `androidx.compose.*` to
`com.tencent.kuikly.compose.*` and validated against both
`2.21.0-2.1.21` and `2.21.0-2.0.21-ohos`.

### HarmonyOS Configuration

**1. Add dependencies in `oh-package.json5`:**
```json
"dependencies": {
    "@kuiklybase/knoi": "0.0.x",
    "@kuiklybase/resource_compose": "0.0.x"
}
```

**2. Configure linker options:**
```kotlin
kotlin {
    ohosArm64 {
        binaries.sharedLib {
            linkerOpts("-L${projectDir}/libs/", "-lresource_compose")
        }
    }
}
```

**3. Copy HarmonyOS SO library:**  
Copy `libresource_compose.so` from `oh_modules/@qqlive/resource_compose/libs/arm64-v8a` to your project's `libs` directory.

**4. Initialize resource compose:**
```typescript
import { initResourceCompose } from 'resource_compose';
initResourceCompose(this.context)
```

### iOS Configuration

**1. Set resource bundle path in `gradle.properties`:**
```
resource.targetCopyDir=../Assets/
```

**2. Configure podspec:**
```ruby
spec.resource_bundles = { spec.name => ['Assets/tmm-resources-apple/*.{xcassets,bundle}'] }
```

**3. Initialize pod name:**
```kotlin
com.tencent.tmm.kmmresource.resource.utils.podName = "YourPodName" 
com.tencent.tmm.kmmresource.resource.utils.isDebug = true
```

## Resource Organization

```
commonMain
└── resources
    └── MR
        ├── base
        │   ├── strings.xml
        │   └── plurals.xml
        ├── colors
        │   └── colors.xml
        ├── images
        │   ├── home_back@1x.webp
        │   └── home_back@2x.webp
        ├── fonts
        │   └── qqlive_font.otf
        ├── files
        │   └── file_name.txt
        └── assets
            └── file_name.txt
```

## Usage Examples

### String Resources
**XML Definition:**
```xml
<resources>
    <string name="hello_world">Hello World</string>
    <string name="my_string_formatted">Formatted: %s</string>
</resources>
```

**Code Access:**
```kotlin
// Common
val basicString = MR.strings.hello_world
val formattedString = MR.strings.my_string_formatted

// Compose
Text(stringResource(MR.strings.hello_world))
Text(stringResource(MR.strings.my_string_formatted, "value"))
```

### Image Resources
**Asset Placement:**
```
commonMain/resources/MR/images/home_black_18@1x.webp
commonMain/resources/MR/images/home_black_18@2x.webp
```

**Code Access:**
```kotlin
// Common
val imageRef = MR.images.home_black_18

// Compose
Image(painterResource(MR.images.home_black_18), "icon")
```

### Color Resources
**XML Definition:**
```xml
<resources>
    <color name="primary">#B02743FF</color>
</resources>
```

**Code Access:**
```kotlin
// Common
val colorRef = MR.colors.primary

// Compose
Box(modifier = Modifier.background(colorResource(MR.colors.primary)))
```

## Implementation Architecture

### MR Class Generation
```kotlin
// COMMON
expect object MR {
    object strings : ResourceContainer<StringResource> {
        val hello_world: StringResource
    }
}

// ANDROID
actual object MR {
    actual object strings : ResourceContainer<StringResource> {
        actual val hello_world = StringResource(R.string.hello_world)
    }
}

// HARMONYOS
actual object MR {
    actual object strings : ResourceContainer<StringResource> {
        actual val hello_world = StringResource("module_hello_world")
    }
}
```

### Platform-Specific Implementations
| Resource Type | Android | HarmonyOS | iOS |
|---------------|---------|-----------|-----|
| `StringResource` | R.id | resName | bundle+resourceId |
| `ColorResource` | R.color | resName | bundle+name |
| `ImageResource` | R.drawable | resName | bundle+assetName |
| `FontResource` | R.font | path | bundle+fontName |

## HarmonyOS Bridge Implementation

**Service Interface:**
```kotlin
@ServiceConsumer
interface OhosResourceService {
    fun getString(resName: String): String?
    fun getString(resName: String, vararg args: Any): String?
    fun getPlural(resName: String, args: Number): String?
    fun getImage(resName: String): ArrayBuffer?
    fun getColor(resName: String): Int?
    fun getImageBase64(resName: String): String?
    fun getFile(resName: String): ArrayBuffer?
}
```

**Usage Example:**
```kotlin
object OhosKmmResourceManager {
    fun getString(resName: String): String? {
        return getOhosResourceServiceApi().getString(resName)
    }
    
    fun getColor(resName: String): Int? {
        return getOhosResourceServiceApi().getColor(resName)
    }
}
```

## Troubleshooting

**Sync Error:**
```
Caused by: java.lang.RuntimeException: Configure tmm-resources multiplatformResourcesPackage
```
**Solution:** Ensure required parameters are set:
```kotlin
multiplatformResources {
    multiplatformResourcesPackage = "com.your.package" 
    multiplatformResourcesPrefix = "your_prefix"
}
```
