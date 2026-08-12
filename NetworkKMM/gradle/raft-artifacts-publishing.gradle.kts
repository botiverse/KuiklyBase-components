import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

// Exact source provenance for every NetworkKMM Maven publication. Raft is
// mirrored from the authenticated GitHub Packages authority by a separate
// create-only primary-file writer; Gradle must never write Raft sidecars.
val networkSourceSha = providers.gradleProperty("networkSourceSha")
    .orElse(providers.environmentVariable("NETWORK_SOURCE_SHA"))
    .map { value ->
        require(value.matches(Regex("[0-9a-f]{40}"))) {
            "networkSourceSha must be the exact 40-character lowercase commit SHA"
        }
        value
    }

extensions.configure<PublishingExtension> {
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
