import java.util.Properties

plugins {
    id("java-library")
    alias(libs.plugins.jetbrainsKotlinJvm)
    id("java-gradle-plugin")
}

dependencies {
    implementation(gradleKotlinDsl())
    compileOnly(libs.kotlinGradlePlugin)
    compileOnly(libs.androidGradlePlugin)
    // compileOnly(libs.kotlinCompilerEmbeddable)
    compileOnly(libs.androidSdkCommon)
    implementation(libs.kotlinPoet)
    implementation(libs.kotlinxSerialization)
    implementation(libs.apacheCommonsText)
    implementation(libs.commonsCodec)
    implementation(libs.gson)
}

gradlePlugin {
    plugins {
        create("multiplatform-resources") {
            id = "com.tencent.kuiklybase.resource.generator"
            implementationClass = "com.tencent.tmm.kmmgradle.MultiplatformResourcesPlugin"

            displayName = "MR resources generator plugin"
            description = "Plugin to provide access to the resources on OHos"
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Pin the Kotlin bytecode target too. `java {}` only governs Java sources; without
// this the Kotlin classes are emitted at the build JDK's version (classfile 66 /
// Java 22 when built on JDK 22), which JDK <= 21 consumers (JBR21 Gradle daemons,
// mobile CI lanes) cannot load — UnsupportedClassVersionError.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

apply(from = file(rootProject.file("gradle/publishing.gradle")))
