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

rootProject.name = "NetworkKMM"
// Normal (non-OHOS) build tree: upstream Kotlin toolchain, android + ios
// targets, official dependencies. The OHOS modules live in the parallel tree
// selected with `-c settings.ohos.gradle.kts` (kuikly dual-tree convention,
// task #18). Modules present in both trees pick their build file per tree
// via buildFileName.
include(":androidApp")
include(":network")
// Sample-only facade shared by androidApp/iosApp. This module deliberately
// stays outside the OHOS build tree and every Maven publication task so demo
// APIs cannot leak into the production :network SDK.
include(":network-demo")
include(":network-android-curl-runtime")
if (gradle.startParameter.projectProperties.containsKey("androidCurlSpike")) {
    include(":android-curl-spike")
    project(":android-curl-spike").projectDir = file("spikes/android-curl")
}
