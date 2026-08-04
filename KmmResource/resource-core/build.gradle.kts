import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate

plugins {
    kotlin("multiplatform")
    kotlin("native.cocoapods")
    id("com.android.library")
    id("com.tencent.kuiklybase.knoi.plugin")
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    // 使用默认层级结构模板
    KotlinHierarchyTemplate.default


    // Android平台
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "1.8"
            }
        }

        // 添加以下配置，将Android平台打包到组件产物中
        publishAllLibraryVariants()
        publishLibraryVariantsGroupedByFlavor = true
        publishLibraryVariants("release", "debug")
    }

    // iOS平台
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    cocoapods {
        name = "resource-core"
        summary = "Some description for the Shared Module"
        homepage = "Link to the Shared Module homepage"
        version = "1.0"
        ios.deploymentTarget = "11.0"
//        podfile = project.file("../iosApp/Podfile")
        framework {
            baseName = "resource_core"
        }
    }
    ohosArm64 {
        val main by compilations.getting
        val interop by main.cinterops.creating {
            includeDirs("$projectDir/src/nativeInterop/cinterop/cpp/include")
        }
    }

    // region Disable K2 on iOS targets.
    val k1Targets = listOf("ios", "meta")
    targets.all {
        // 过滤掉 iosArm64, iosSimulatorArm64, metadata 等
        if (k1Targets.none { targetName.startsWith(it, true) }) {
            compilations.all {
                kotlinOptions.languageVersion = "2.0"
            }
        } else {
            compilations.all {
                kotlinOptions.languageVersion = "1.9"
            }
        }
    }
    // endregion

    sourceSets {
        val commonMain by getting {
            dependencies {
                // put your multiplatform dependencies here
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.appCompatResources)
            }
        }

        val ohosArm64Main by getting {
            dependencies {}
        }

    }

}

android {
    namespace = "com.tencent.tmm.kmmresource"
    compileSdk = 33
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}


knoi {
    ignoreTypeAssert = true
    tsGenDir = projectDir.absolutePath + "/ts-api/"
}


apply(from = file(rootProject.file("gradle/publishing.gradle")))
