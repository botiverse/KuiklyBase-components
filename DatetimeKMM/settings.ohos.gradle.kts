pluginManagement {
    repositories {
        maven("https://mirrors.tencent.com/nexus/repository/maven-public/")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://mirrors.tencent.com/nexus/repository/maven-public/")
        google()
        mavenCentral()
    }
}

// The OHOS tree is deliberately separate. It publishes the same group and
// artifact under the `-ohos` version suffix, using K/N 2.0.21-KBA-010.
rootProject.name = "DatetimeKMM"
rootProject.buildFileName = "build.ohos.gradle.kts"

include(":datetime")
project(":datetime").buildFileName = "build.ohos.gradle.kts"
