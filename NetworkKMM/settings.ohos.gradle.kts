pluginManagement {
    repositories {
        mavenLocal()
        maven("https://oss.sonatype.org/content/repositories/snapshots")
        maven("https://oss.sonatype.org/content/groups/public/")
        maven("https://mirrors.tencent.com/nexus/repository/maven-public/")

        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        maven("https://oss.sonatype.org/content/repositories/snapshots")
        maven("https://oss.sonatype.org/content/groups/public/")
        maven("https://mirrors.tencent.com/nexus/repository/maven-public/")

        google()
        mavenCentral()
    }
}

// OHOS build tree (kuikly dual-tree convention, task #18): compiled with the
// KBA Kotlin toolchain and the KBA coroutines/atomicfu forks, publishing the
// SAME coordinates as the normal tree under the `-ohos` version suffix with
// its own root kotlinMultiplatform module (variants: ohosArm64). Consumers'
// ohos build selects this tree by version, exactly like kuikly-open
// (2.21.0-2.1.21 vs 2.21.0-2.0.21-ohos).
rootProject.name = "NetworkKMM"

val ohosBuildFileName = "build.ohos.gradle.kts"
rootProject.buildFileName = ohosBuildFileName

include(":network")
project(":network").buildFileName = ohosBuildFileName

include(":network-host-native-tests")
include(":network-sample")
include(":network-ohos-runtime")
include(":network-ohos-runtime-gradle-plugin")
