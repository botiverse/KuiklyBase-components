import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

// Additive publication destination for Raft Artifacts. GitHub Packages and
// Maven Central remain configured by each producer module; this convention
// only adds the second repository and exact source provenance to every Maven
// publication in the NetworkKMM build tree.
val raftArtifactsUrl = providers.gradleProperty("raftArtifactsUrl")
    .orElse(providers.environmentVariable("RAFT_ARTIFACTS_URL"))
    .orElse("https://maven.artifacts.botiverse.dev")
val raftArtifactsUsername = providers.gradleProperty("raftArtifactsUsername")
    .orElse(providers.environmentVariable("RAFT_ARTIFACTS_USERNAME"))
    .orElse("raft-ci")
val raftArtifactsToken = providers.gradleProperty("raftArtifactsToken")
    .orElse(providers.environmentVariable("RAFT_ARTIFACTS_PUBLISH_TOKEN"))
val networkSourceSha = providers.gradleProperty("networkSourceSha")
    .orElse(providers.environmentVariable("NETWORK_SOURCE_SHA"))
    .map { value ->
        require(value.matches(Regex("[0-9a-f]{40}"))) {
            "networkSourceSha must be the exact 40-character lowercase commit SHA"
        }
        value
    }

extensions.configure<PublishingExtension> {
    repositories {
        maven {
            name = "raftArtifacts"
            url = uri(raftArtifactsUrl)
            credentials {
                username = raftArtifactsUsername.orNull.orEmpty()
                password = raftArtifactsToken.orNull.orEmpty()
            }
        }
    }

    publications.withType<MavenPublication>().configureEach {
        pom {
            // Both fields are intentionally provider-backed. Generating a POM
            // for publication fails when the release entrypoint did not bind
            // the clean checkout through `git rev-parse HEAD`.
            properties.put("dev.raft.sourceSha", networkSourceSha)
            scm {
                tag.set(networkSourceSha)
            }
        }
    }
}
