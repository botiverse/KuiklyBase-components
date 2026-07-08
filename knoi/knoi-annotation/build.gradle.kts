plugins {
    kotlin("multiplatform")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    targetHierarchy.default()
    js()
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    // NetworkKMM Darwin behavior-test lane (-PdarwinBehaviorTests): macos
    // variant so the lane's macosArm64 consumer can resolve knoi from
    // mavenLocal. Conditional so normal (linux-hosted) publishes are unchanged.
    if (providers.gradleProperty("darwinBehaviorTests").isPresent) {
        macosArm64()
    }

    // 以上平台无需实现，仅仅通过编译

    jvm {
        compilations.all {
            kotlinOptions {
                jvmTarget = "1.8"
            }
        }
    }

    ohosArm64 {}

    sourceSets {
        val commonMain by getting {
        }
    }
}

tasks.withType<Jar>().configureEach {
    manifest {
        attributes(
            "Implementation-Title" to "knoi-annotation",
            "Implementation-Version" to project.version,
        )
    }
}