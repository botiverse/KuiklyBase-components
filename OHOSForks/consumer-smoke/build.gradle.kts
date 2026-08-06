plugins {
    kotlin("multiplatform")
}

val atomicfuVersion = providers.gradleProperty("atomicfuVersion").get()
val coroutinesVersion = providers.gradleProperty("coroutinesVersion").get()

kotlin {
    ohosArm64 {
        binaries.executable {
            entryPoint = "dev.raft.ohosforks.main"
            linkerOpts(
                "-lhilog_ndk.z",
                "-L/opt/harmonyos-tools/command-line-tools/sdk/default/openharmony/native/llvm/lib/aarch64-linux-ohos",
                "-lunwind",
            )
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:atomicfu:$atomicfuVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
        }
    }
}
