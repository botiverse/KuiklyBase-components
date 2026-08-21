import org.gradle.api.publish.maven.MavenPublication

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

// --- Legal / provenance packaging (OHOS tree) -----------------------------
// The ohosArm64 KLIB binary does NOT embed source-set resources in KBA
// 2.0.21-KBA-010 (konanc receives no resource flag; default/resources/ stays
// empty), and it is deliberately NOT post-processed, so its manifest stays
// unmodified for toolchain compatibility. The license/notice/provenance bytes
// for this publication are carried by the OHOS root metadata JAR and the
// root/ohosArm64 sources JARs (wired below) plus the POM Apache-2.0 metadata.
// The DatetimeKMM CI OHOS gate republishes to an isolated local repository and
// asserts those bytes in the metadata + sources JARs (not the KLIB). See
// legal/META-INF/PROVENANCE.md, "Publication delivery contract".
// (commonMain.resources.srcDir(legal) in the source sets above is retained only
// as forward-compatible intent for a future toolchain that embeds native
// resources; it contributes nothing to the current KLIB.)
val legalDir = rootProject.file("legal")

tasks.withType<Jar>().configureEach {
    from(legalDir)
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

afterEvaluate {
    tasks.matching { it.name.endsWith("SourcesJar", ignoreCase = true) }.configureEach {
        val jarTask = this as org.gradle.jvm.tasks.Jar
        jarTask.from(legalDir)
        jarTask.duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
}

// Raft-only lane (task #106): no remote repository in the build. The OHOS
// tree stages into the same task-scoped file Maven repository as the normal
// tree; the reviewed uploader carries the staged bytes to Raft.
val stagingDir = System.getenv("DATETIME_STAGING_DIR")
    ?: rootProject.layout.buildDirectory.dir("publication-staging").get().asFile.absolutePath

// Provider-backed, lazy: POM generation fails closed at publish time when the
// exact dispatch SHA is not bound; non-publish builds never read it.
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
                tag.set(datetimeSourceSha)
            }
            properties.put("dev.raft.sourceSha", datetimeSourceSha)
        }
    }
}
