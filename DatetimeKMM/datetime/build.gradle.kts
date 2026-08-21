import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.publish.maven.MavenPublication

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    `maven-publish`
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    explicitApi()
    applyDefaultHierarchyTemplate()

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
        publishLibraryVariants("release")
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            resources.srcDir(rootProject.file("legal"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "build.raft.kuiklybase.datetime"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
    }
}

// --- Legal / provenance packaging -----------------------------------------
// In this toolchain KMP resource propagation does NOT place commonMain
// resources into the published metadata JAR, the Android AAR, or the sources
// JARs, so the Apache-2.0 LICENSE/NOTICE and the provenance catalog are wired
// into every published artifact explicitly. The DatetimeKMM CI gate republishes
// to an isolated local repository and asserts these exact bytes in each variant
// (see .github/workflows/datetimekmm-tests.yml).
val legalDir = rootProject.file("legal")

// Root metadata binary JAR: carry META-INF/{LICENSE.txt,NOTICE.txt,PROVENANCE.md}.
tasks.withType<Jar>().configureEach {
    from(legalDir)
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

// Every platform sources JAR (metadata/android/ios*) carries the same files.
// The Kotlin sourcesJar copy spec is finalized after the build script body, so
// these tasks are targeted by name inside afterEvaluate and configured through
// the stable jvm Jar type (the decorated task defeats a bundling-Jar cast).
afterEvaluate {
    tasks.matching { it.name.endsWith("SourcesJar", ignoreCase = true) }.configureEach {
        val jarTask = this as org.gradle.jvm.tasks.Jar
        jarTask.from(legalDir)
        jarTask.duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
}

// Android: place the same files inside the published AAR (classes.jar). AGP
// strips /META-INF/LICENSE* and /META-INF/NOTICE* by default (leading-slash
// patterns), so re-allow exactly those four and keep every other exclusion.
android.packagingOptions.resources.excludes.removeAll {
    it == "/META-INF/LICENSE" || it == "/META-INF/LICENSE.txt" ||
        it == "/META-INF/NOTICE" || it == "/META-INF/NOTICE.txt"
}
android.sourceSets.getByName("main").resources.srcDir(legalDir)

// Raft Artifacts is the only publish destination for new versions (artin's
// standing rule: new publishes go to our own registry, never GitHub Packages).
// The build itself has NO remote repository: publications are staged into a
// task-scoped file repository below, and the reviewed uploader carries those
// exact staged bytes to Raft (scripts/raft-publish.py), so what is uploaded
// is byte-identical to what was hashed at staging by construction.
//
// The staging repository is intentionally NOT mavenLocal: DATETIME_STAGING_DIR
// points at a per-job directory that the workflow creates empty and the lane
// asserts exhaustively before use, so no old version, maven-metadata.xml or
// sidecar can leak into the authority manifest.
val stagingDir = System.getenv("DATETIME_STAGING_DIR")
    ?: rootProject.layout.buildDirectory.dir("publication-staging").get().asFile.absolutePath

// Source provenance for the POM (PR #120 contract: source reverse-lookupable).
// Provider-backed and lazy: POM generation fails closed at publish time when
// the release entrypoint did not bind the exact 40-hex source SHA, while
// non-publish builds never read it.
val datetimeSourceSha = providers.environmentVariable("DATETIME_SOURCE_SHA")
    .map { value ->
        require(value.matches(Regex("[0-9a-f]{40}"))) {
            "DATETIME_SOURCE_SHA must be the exact 40-character lowercase commit SHA"
        }
        value
    }

publishing {
    repositories {
        maven {
            name = "staging"
            url = uri(stagingDir)
        }
    }

    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("KuiklyBase Datetime")
            description.set("Minimal wall-clock and fresh system-timezone primitives for Kuikly KMP applications.")
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
                tag.set(datetimeSourceSha)
            }
            properties.put("dev.raft.sourceSha", datetimeSourceSha)
        }
    }
}
