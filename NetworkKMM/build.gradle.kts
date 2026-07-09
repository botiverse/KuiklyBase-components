plugins {
    // trick: for the same plugin versions in all sub-modules
    // Normal (non-OHOS) tree: upstream Kotlin. The OHOS tree
    // (build.ohos.gradle.kts, selected via -c settings.ohos.gradle.kts) keeps
    // the KBA toolchain; ksp/knoi are OHOS-tree concerns and live there.
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

ext {
    group = "com.tencent.kuiklybase"
    version = "1.0.0"
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
