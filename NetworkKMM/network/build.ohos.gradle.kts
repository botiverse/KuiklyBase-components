import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.`maven-publish`
import org.gradle.kotlin.dsl.signing
import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate
import java.util.*

// OHOS build tree for :network (task #18 dual-tree split). Compiled with the
// KBA Kotlin toolchain; declares the KBA coroutines/atomicfu forks DIRECTLY —
// no force()/versionMapping machinery, published metadata is honest because
// the declarations are. Publishes com.tencent.kuiklybase:network at
// `${mavenVersion}-ohos` with its own root kotlinMultiplatform module whose
// only native variant is ohosArm64.
plugins {
    kotlin("multiplatform")
    `maven-publish`
    signing
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
/**
 * Same generated version constant as the normal tree. Both trees derive from
 * the one `mavenVersion` property; this tree only adds the `-ohos` coordinate
 * suffix, which is stripped so the reported library version matches.
 */
val generateNetworkKmmVersion by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/networkKmmVersion/commonMain/kotlin")
    val versionValue = providers.provider { project.version.toString().removeSuffix("-ohos") }
    inputs.property("version", versionValue)
    outputs.dir(outputDir)

    doLast {
        val target = outputDir.get().asFile.resolve("com/tencent/kmm/network/export")
        target.mkdirs()
        target.resolve("NetworkKmmVersion.kt").writeText(
            """
            package com.tencent.kmm.network.export

            /** Generated from the published version; do not edit. */
            internal const val NETWORK_KMM_VERSION: String = "${versionValue.get()}"
            """.trimIndent() + "\n"
        )
    }
}

kotlin {
    KotlinHierarchyTemplate.default

    ohosArm64 {

        val main by compilations.getting

        val interop by main.cinterops.creating {
            includeDirs("${project.rootDir}/ohosApp/pbcurlwrapper/src/main/cpp/wrapper/include")
        }

        compilations.forEach {
            it.compilerOptions.options.optIn.addAll(
                "kotlinx.cinterop.ExperimentalForeignApi",
                "kotlin.experimental.ExperimentalNativeApi",
            )
        }
        binaries.all {
            freeCompilerArgs += "-Xadd-light-debug=enable"
            freeCompilerArgs += "-Xbinary=sourceInfoType=libbacktrace"
        }
        binaries {
            sharedLib {
                baseName = "kn"
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            // Pass the task provider, not a bare directory, so every consumer
            // (compile, sourcesJar, publication) inherits the dependency. A
            // bare path made sourcesJar read the output without declaring it,
            // which Gradle rejects.
            kotlin.srcDir(generateNetworkKmmVersion)
            dependencies {
                // KBA forks, declared plainly: this tree's graph and its
                // published metadata are the same statement.
                implementation(libs.kotlinx.coroutines.core.kba)
                implementation(libs.atomicfu.kba)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val ohosArm64Main by getting {
            dependencies {
                // 鸿蒙依赖
            }
        }
    }
}

// Stub secrets to let the project sync and build without the publication values set up
extra["signing.keyId"] = null
extra["signing.password"] = null
extra["signing.secretKeyRingFile"] = null
extra["ossrhUsername"] = null
extra["ossrhPassword"] = null
extra["githubPackagesOwner"] = null
extra["githubPackagesRepository"] = null
extra["githubPackagesUsername"] = null
extra["githubPackagesToken"] = null

fun getExtraString(name: String) = try {
    extra[name]?.toString()
} catch (ignore: Exception) {
    null
}

fun String?.validPublishValue(): String? = takeIf { !it.isNullOrBlank() && it != "null" }

fun getOptionalPublishProperty(name: String, vararg envNames: String): String? {
    findProperty(name)?.toString().validPublishValue()?.let { return it }
    getExtraString(name).validPublishValue()?.let { return it }
    return envNames.firstNotNullOfOrNull { System.getenv(it).validPublishValue() }
}

fun getRequiredPublishProperty(name: String, vararg envNames: String): String {
    return getOptionalPublishProperty(name, *envNames)
        ?: error("Missing publish property: $name")
}

// Grabbing secrets from local.properties file or from environment variables, which could be used on CI
val secretPropsFile = project.rootProject.file("local.properties")
if (secretPropsFile.exists()) {
    secretPropsFile.reader().use {
        Properties().apply {
            load(it)
        }
    }.onEach { (name, value) ->
        extra[name.toString()] = value
    }
} else {
    extra["signing.keyId"] = System.getenv("SIGNING_KEY_ID")
    extra["signing.password"] = System.getenv("SIGNING_PASSWORD")
    extra["signing.secretKeyRingFile"] = System.getenv("SIGNING_SECRET_KEY_RING_FILE")
    extra["ossrhUsername"] = System.getenv("OSSRH_USERNAME")
    extra["ossrhPassword"] = System.getenv("OSSRH_PASSWORD")
}

val publishFile = project.rootProject.file("gradle.properties")
if (publishFile.exists()) {
    publishFile.reader().use {
        Properties().apply {
            load(it)
        }
    }.onEach { (name, value) ->
        extra[name.toString()] = value
    }
}

val publishGroupID = getRequiredPublishProperty("gruopID", "MAVEN_GROUP_ID")
// OHOS tree: same coordinates, `-ohos` version suffix (kuikly convention).
val publishVersion = getRequiredPublishProperty("mavenVersion", "MAVEN_VERSION") + "-ohos"
val publishArtifactID = getRequiredPublishProperty("artifactID", "MAVEN_ARTIFACT_ID")
val githubRepositoryEnv = System.getenv("GITHUB_REPOSITORY")
val githubPackagesOwner = (
    getOptionalPublishProperty("githubPackagesOwner", "GITHUB_PACKAGES_OWNER")
        ?: githubRepositoryEnv?.substringBefore("/")
        ?: "bytemain"
    ).lowercase(Locale.US)
val githubPackagesRepository = getOptionalPublishProperty(
    "githubPackagesRepository",
    "GITHUB_PACKAGES_REPOSITORY",
) ?: githubRepositoryEnv?.substringAfter("/") ?: "KuiklyBase-components"
val githubPackagesUsername = getOptionalPublishProperty(
    "githubPackagesUsername",
    "GITHUB_PACKAGES_USERNAME",
    "GITHUB_ACTOR",
) ?: getOptionalPublishProperty("gpr.user")
val githubPackagesToken = getOptionalPublishProperty(
    "githubPackagesToken",
    "GITHUB_PACKAGES_TOKEN",
    "GITHUB_TOKEN",
    "GH_TOKEN",
) ?: getOptionalPublishProperty("gpr.key")
val signingKeyId = getOptionalPublishProperty("signing.keyId", "SIGNING_KEY_ID")
val signingPassword = getOptionalPublishProperty("signing.password", "SIGNING_PASSWORD")
val signingSecretKeyRingFile = getOptionalPublishProperty(
    "signing.secretKeyRingFile",
    "SIGNING_SECRET_KEY_RING_FILE",
)
val shouldSignPublications = listOf(
    signingKeyId,
    signingPassword,
    signingSecretKeyRingFile,
).all { !it.isNullOrBlank() }

val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

publishing {
    // Configure maven central repository
    repositories {
        maven {
            name = "maven"
//            val releasesRepoUrl = "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
//            val snapshotsRepoUrl = "https://s01.oss.sonatype.org/content/repositories/snapshots/"
            val releasesRepoUrl = uri("https://oss.sonatype.org/service/local/staging/deploy/maven2")
            val snapshotsRepoUrl = uri("https://oss.sonatype.org/content/repositories/snapshots")
            url = if (publishVersion.endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
            credentials {
                username = getExtraString("ossrhUsername")
                password = getExtraString("ossrhPassword")
            }
        }
        maven {
            name = "githubPackages"
            url = uri("https://maven.pkg.github.com/$githubPackagesOwner/$githubPackagesRepository")
            credentials {
                username = githubPackagesUsername.orEmpty()
                password = githubPackagesToken.orEmpty()
            }
        }
    }

    // Configure all publications
    publications.withType<MavenPublication> {
        // Stub javadoc.jar artifact
//        artifact(javadocJar.get())

        // Provide artifacts information requited by Maven Central
        pom {
            name.set(publishArtifactID)
            description.set("Tencent Http Service Library.")
            url.set("https://github.com/bytemain/KuiklyBase-components.git")

            licenses {
                license {
                    name.set("Apache License 2.0")
                    url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
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
                url.set("https://github.com/bytemain/KuiklyBase-components")
            }
        }
    }
}

extensions.configure<SigningExtension> {
    if (shouldSignPublications) {
        sign(publishing.publications)
    }
}
