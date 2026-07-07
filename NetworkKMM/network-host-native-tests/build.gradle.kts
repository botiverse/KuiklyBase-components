plugins {
    kotlin("multiplatform")
}

kotlin {
    linuxX64 {
        val test by compilations.getting
        val interop by test.cinterops.creating {
            definitionFile.set(project.file("src/nativeInterop/cinterop/interop.def"))
            includeDirs("${project.rootDir}/ohosApp/pbcurlwrapper/src/main/cpp/wrapper/include")
        }

        compilations.forEach {
            it.compilerOptions.options.optIn.addAll(
                "kotlinx.cinterop.ExperimentalForeignApi",
                "kotlin.experimental.ExperimentalNativeApi",
            )
        }
    }

    sourceSets {
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
