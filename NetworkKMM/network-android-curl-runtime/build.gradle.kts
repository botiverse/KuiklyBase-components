import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.`maven-publish`
import org.gradle.kotlin.dsl.signing
import org.gradle.plugins.signing.SigningExtension
import java.util.Locale
import java.util.Properties

plugins {
    id("com.android.library")
    `maven-publish`
    signing
}

android {
    namespace = "com.tencent.kuiklybase.network.android.curl.runtime"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
    }

    sourceSets {
        getByName("main") {
            // The normal NetworkKMM AAR stays zero-so. Consumers opt into
            // these exact native payloads with this separate coordinate.
            jniLibs.srcDir(rootProject.file("network/libs/android"))
        }
    }

    publishing {
        singleVariant("release")
    }
}

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

val publishVersion = project.version.toString()
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
val shouldSignPublications = listOf(
    getOptionalPublishProperty("signing.keyId", "SIGNING_KEY_ID"),
    getOptionalPublishProperty("signing.password", "SIGNING_PASSWORD"),
    getOptionalPublishProperty("signing.secretKeyRingFile", "SIGNING_SECRET_KEY_RING_FILE"),
).all { !it.isNullOrBlank() }

afterEvaluate {
    publishing {
        repositories {
            maven {
                name = "maven"
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
        publications {
            create<MavenPublication>("androidCurlRuntime") {
                artifactId = "network-android-curl-runtime"
                from(components["release"])
                pom {
                    name.set("network-android-curl-runtime")
                    description.set("Opt-in Android native curl runtime payload for Tencent Http Service Library.")
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
    }

    extensions.configure<SigningExtension> {
        if (shouldSignPublications) {
            sign(publishing.publications)
        }
    }
}
