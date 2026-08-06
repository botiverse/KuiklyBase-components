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
        exclusiveContent {
            forRepository {
                maven {
                    name = "ohosForkStaging"
                    url = uri(
                        System.getProperty(
                            "maven.repo.local",
                            "${System.getProperty("user.home")}/.m2/repository",
                        ),
                    )
                    metadataSources {
                        gradleMetadata()
                        mavenPom()
                        artifact()
                    }
                }
            }
            filter {
                includeModule("org.jetbrains.kotlinx", "atomicfu")
                includeModule("org.jetbrains.kotlinx", "atomicfu-ohosarm64")
                includeModule("org.jetbrains.kotlinx", "kotlinx-coroutines-core")
                includeModule("org.jetbrains.kotlinx", "kotlinx-coroutines-core-ohosarm64")
            }
        }
        maven("https://mirrors.tencent.com/nexus/repository/maven-public/")
        mavenCentral()
    }
}

rootProject.name = "kuiklybase-ohos-forks-consumer-smoke"
