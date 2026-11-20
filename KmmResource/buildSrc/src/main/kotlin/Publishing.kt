@file:Suppress("UnstableApiUsage")

import org.gradle.api.*
import org.gradle.api.artifacts.dsl.*
import org.gradle.api.provider.*
import org.gradle.api.publish.maven.*
import org.gradle.plugins.signing.*
import java.net.*
import java.util.Properties

infix fun <T> Property<T>.by(value: T) {
    set(value)
}

fun MavenPom.configureMavenCentralMetadata(project: Project) {
    name by project.name
    description by "Kotlin Multiplatform Mobile Resource"
    url by "https://github.com/bytemain/KuiklyBase-components"

    licenses {
        license {
            name by "The Apache Software License, Version 2.0"
            url by "https://www.apache.org/licenses/LICENSE-2.0.txt"
            distribution by "repo"
        }
    }

    developers {
        developer {
            id by "bytemain"
            name by "bytemain"
            organization by "bytemain"
            organizationUrl by "https://github.com/bytemain"
        }
    }

    scm {
        url by "https://github.com/bytemain/KuiklyBase-components"
    }
}

fun configureMavenPublication(rh: RepositoryHandler, project: Project) {
    System.getenv("KMMRESOURCE_STAGING_DIR")?.let { directory ->
        rh.maven {
            name = "releaseStaging"
            url = project.file(directory).toURI()
        }
    }
    rh.maven {
        val releasesRepoUrl = "https://oss.sonatype.org/service/local/staging/deploy/maven2"
        val snapshotsRepoUrl = "https://oss.sonatype.org/content/repositories/snapshots"
        val version = project.version.toString()
        url = URI(if (version.endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl)
        credentials {
            username = project.getSensitiveProperty("libs.sonatype.user")
            password = project.getSensitiveProperty("libs.sonatype.password")
        }
    }
    rh.maven {
        val githubRepositoryEnv = System.getenv("GITHUB_REPOSITORY")
        val owner = project.getPublishProperty("githubPackagesOwner", "GITHUB_PACKAGES_OWNER")
            ?: githubRepositoryEnv?.substringBefore("/")
            ?: "bytemain"
        val repository = project.getPublishProperty("githubPackagesRepository", "GITHUB_PACKAGES_REPOSITORY")
            ?: githubRepositoryEnv?.substringAfter("/")
            ?: "KuiklyBase-components"
        name = "githubPackages"
        url = URI("https://maven.pkg.github.com/$owner/$repository")
        credentials {
            username = project.getPublishProperty("githubPackagesUsername", "GITHUB_PACKAGES_USERNAME")
                ?: project.getPublishProperty("gpr.user", "GITHUB_ACTOR")
                ?: ""
            password = project.getPublishProperty("githubPackagesToken", "GITHUB_PACKAGES_TOKEN")
                ?: project.getPublishProperty("gpr.key", "GITHUB_TOKEN")
                ?: ""
        }
    }
}

fun signPublicationIfKeyPresent(project: Project, publication: MavenPublication) {
    val hasSigningConfig =
        project.getSensitiveProperty("signing.keyId").isNullOrBlank().not() ||
            project.getSensitiveProperty("signing.key").isNullOrBlank().not() ||
            System.getenv("ORG_GRADLE_PROJECT_signingKey").isNullOrBlank().not()
    if (!hasSigningConfig) return

    project.extensions.configure<SigningExtension>("signing") {
        sign(publication)
    }
}

private fun Project.getSensitiveProperty(name: String): String? {
    return localProperties().getProperty(name)
}

private fun Project.getPublishProperty(gradlePropertyName: String, envName: String): String? {
    return findProperty(gradlePropertyName)?.toString()?.takeIf { it.isNotBlank() }
        ?: localProperties().getProperty(gradlePropertyName)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envName)?.takeIf { it.isNotBlank() }
}

private fun Project.localProperties(): Properties {
    return rootProject.file("local.properties")
        .takeIf { it.exists() }
        ?.let { file ->
            Properties().apply {
                file.inputStream().use(::load)
            }
        } ?: Properties()
}
