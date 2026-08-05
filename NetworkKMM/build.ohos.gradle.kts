plugins {
    // OHOS build tree (task #18 dual-tree): the KBA toolchain and the
    // OHOS-only plugin stack live here; the normal tree (build.gradle.kts)
    // is upstream Kotlin with android + ios only.
    id("com.android.application").version("8.13.2").apply(false)
    id("com.android.library").version("8.13.2").apply(false)
    kotlin("multiplatform").version("2.0.21-KBA-010").apply(false)
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    id("com.tencent.kuiklybase.knoi.plugin").version("0.0.4").apply(false)
}

allprojects {
    group = project.findProperty("gruopID") as String
    // Same coordinates as the normal tree, `-ohos` version suffix — each tree
    // publishes its OWN root kotlinMultiplatform module and consumers select
    // the tree by version (kuikly convention: 2.21.0-2.1.21 vs
    // 2.21.0-2.0.21-ohos).
    version = "${project.findProperty("mavenVersion") as String}-ohos"

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

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
