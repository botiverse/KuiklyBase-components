import org.gradle.api.publish.maven.MavenPublication
import java.util.Locale

plugins {
    kotlin("multiplatform")
    `maven-publish`
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    explicitApi()
    applyDefaultHierarchyTemplate()

    ohosArm64 {
        val main by compilations.getting
        val timeService by main.cinterops.creating {
            defFile(project.file("src/ohosArm64Main/cinterop/time_service.def"))
            includeDirs(project.file("src/ohosArm64Main/cinterop/include"))
        }
        compilations.configureEach {
            compilerOptions.options.optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
        }
    }

    sourceSets {
        commonMain {
            resources.srcDir(rootProject.file("legal"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

val githubRepository = System.getenv("GITHUB_REPOSITORY") ?: "bytemain/KuiklyBase-components"
val githubOwner = githubRepository.substringBefore('/').lowercase(Locale.US)
val githubRepositoryName = githubRepository.substringAfter('/', "KuiklyBase-components")

publishing {
    repositories {
        maven {
            name = "githubPackages"
            url = uri("https://maven.pkg.github.com/$githubOwner/$githubRepositoryName")
            credentials {
                username = System.getenv("GITHUB_PACKAGES_USERNAME")
                    ?: System.getenv("GITHUB_ACTOR")
                    ?: ""
                password = System.getenv("GITHUB_PACKAGES_TOKEN")
                    ?: System.getenv("GITHUB_TOKEN")
                    ?: ""
            }
        }
    }

    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("KuiklyBase Datetime")
            description.set("Minimal wall-clock and fresh system-timezone primitives for Kuikly OHOS applications.")
            url.set("https://github.com/bytemain/KuiklyBase-components/tree/master/DatetimeKMM")
            licenses {
                license {
                    name.set("Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("bytemain")
                    name.set("bytemain")
                    organization.set("bytemain")
                    organizationUrl.set("https://github.com/bytemain")
                }
            }
            scm {
                connection.set("scm:git:https://github.com/bytemain/KuiklyBase-components.git")
                developerConnection.set("scm:git:ssh://git@github.com/bytemain/KuiklyBase-components.git")
                url.set("https://github.com/bytemain/KuiklyBase-components")
            }
        }
    }
}
