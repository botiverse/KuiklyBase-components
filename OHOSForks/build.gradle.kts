buildscript {
    repositories {
        maven("https://mirrors.tencent.com/nexus/repository/maven-public/")
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        // Keep the Gradle integration first, matching upstream coroutines.
        classpath(
            "org.jetbrains.kotlinx:atomicfu-gradle-plugin:${property("atomicfuBuildPluginVersion")}",
        )
        // The forked compiler plugin must match the pinned KBA compiler exactly.
        classpath("org.jetbrains.kotlin:atomicfu:${property("kotlinVersion")}")
    }
}

plugins {
    kotlin("multiplatform") apply false
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
    delete(subprojects.map { it.layout.buildDirectory })
}
