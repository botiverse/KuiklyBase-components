plugins {
    // trick: for the same plugin versions in all sub-modules
    // Normal (non-OHOS) tree: upstream Kotlin 2.1.21 — matched to the
    // CONSUMER's Kotlin/Native, not to any dependency: K/N klib ABI is
    // strictly forward-only (a 2.1.21 consumer cannot read 2.2.x klibs —
    // raft.14 lesson, caught by Codex on mobile PR #422), so the producer
    // toolchain may not exceed mobile's Kotlin until mobile itself bumps.
    // This is also why OkHttp stays on alpha.14 (5.x stable pulls stdlib
    // 2.2.21 which breaks the 2.1.21 metadata transform). Both move together
    // when mobile reaches Kotlin 2.2.x — see task #18 trigger list. The OHOS
    // tree (build.ohos.gradle.kts, -c settings.ohos.gradle.kts) keeps the KBA
    // toolchain; ksp/knoi are OHOS-tree concerns and live there.
    id("com.android.application").version("8.13.2").apply(false)
    id("com.android.library").version("8.13.2").apply(false)
    kotlin("multiplatform").version("2.1.21").apply(false)
}

allprojects {
    group = project.findProperty("gruopID") as String
    version = project.findProperty("mavenVersion") as String  // 确保所有模块继承此版本

    repositories {
        mavenLocal()
        maven("https://oss.sonatype.org/content/repositories/snapshots")
        maven("https://oss.sonatype.org/content/groups/public/")
        maven("https://mirrors.tencent.com/nexus/repository/maven-public/")

        google()
        mavenCentral()
    }
}

subprojects {
    pluginManager.withPlugin("maven-publish") {
        apply(from = rootProject.file("gradle/raft-artifacts-publishing.gradle.kts"))
    }
}

ext {
    group = "com.tencent.kuiklybase"
    version = "1.0.0"
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
