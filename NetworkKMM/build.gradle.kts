plugins {
    // trick: for the same plugin versions in all sub-modules
    // Normal (non-OHOS) tree: upstream Kotlin 2.2.21 — matches the stdlib
    // OkHttp 5.x stable pulls (2.2.21), so the commonMain metadata transform
    // never sees a stdlib klib newer than the compiler (the raft.11 lesson,
    // which reproduced on 2.1.21 + stdlib 2.2.21). Consumers on Kotlin 2.1.x
    // read 2.2 metadata fine (N+1 rule). The OHOS tree
    // (build.ohos.gradle.kts, selected via -c settings.ohos.gradle.kts) keeps
    // the KBA toolchain; ksp/knoi are OHOS-tree concerns and live there.
    id("com.android.application").version("8.13.2").apply(false)
    id("com.android.library").version("8.13.2").apply(false)
    kotlin("multiplatform").version("2.2.21").apply(false)
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

ext {
    group = "com.tencent.kuiklybase"
    version = "1.0.0"
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
