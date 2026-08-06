pluginManagement {
    repositories {
        maven("https://mirrors.tencent.com/nexus/repository/maven-public/")
        gradlePluginPortal()
        mavenCentral()
    }

    plugins {
        kotlin("multiplatform") version providers.gradleProperty("kotlinVersion").get()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        maven("https://mirrors.tencent.com/nexus/repository/maven-public/")
        mavenCentral()
    }
}

rootProject.name = "kuiklybase-ohos-forks"

include(":atomicfu")
include(":kotlinx-coroutines-core")
project(":kotlinx-coroutines-core").projectDir = file("coroutines")
