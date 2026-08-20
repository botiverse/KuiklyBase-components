/*
 * Static gate: NetworkUserAgent.LIBRARY_VERSION must equal the published
 * version of whichever tree applies this script.
 *
 * It lives in a shared script because the OHOS tree builds from
 * settings.ohos.gradle.kts / build.ohos.gradle.kts and shares no build logic
 * with the normal tree. A gate wired into only one of them can be bypassed by
 * publishing from the other, which is how `-ohos` artifacts drift.
 *
 * This is a build assertion rather than a unit test on purpose: it compares
 * source text, and the test source sets hold runtime assertions only.
 */

val checkUserAgentLibraryVersion by tasks.registering {
    val userAgentSource = layout.projectDirectory
        .file("src/commonMain/kotlin/com/tencent/kmm/network/export/NetworkUserAgent.kt")
    val declaredVersion = providers.provider { project.version.toString() }
    inputs.file(userAgentSource)
    inputs.property("publishedVersion", declaredVersion)

    doLast {
        val expected = declaredVersion.get()
        val text = userAgentSource.asFile.readText()
        val match = Regex("""LIBRARY_VERSION:\s*String\s*=\s*"([^"]+)"""").find(text)
            ?: throw GradleException(
                "LIBRARY_VERSION not found in ${userAgentSource.asFile.name}; the gate must " +
                    "fail loudly rather than pass because it could not read the constant"
            )
        val actual = match.groupValues[1]

        // The OHOS tree publishes the same constant under a `-ohos` suffixed
        // version, so compare against the base version in both trees.
        val expectedBase = expected.removeSuffix("-ohos")
        if (actual != expectedBase) {
            throw GradleException(
                "NetworkUserAgent.LIBRARY_VERSION is \"$actual\" but this tree publishes " +
                    "\"$expected\"; update the constant with the release bump"
            )
        }
    }
}

tasks.matching { it.name == "check" }.configureEach { dependsOn(checkUserAgentLibraryVersion) }
tasks.matching { it.name.startsWith("publish") }.configureEach {
    dependsOn(checkUserAgentLibraryVersion)
}
