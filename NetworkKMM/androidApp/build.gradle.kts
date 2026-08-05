plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.tencent.kmm.component.template.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.tencent.kmm.component.template.android"
        minSdk = 24
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures {
        // Compose Compiler 版本暂时不支持 Android
        compose = false
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.6.1-dev-17.0.11"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

// Kotlin 2.2 removed the deprecated android kotlinOptions DSL.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}
dependencies {
    // The app still hosts the legacy VBTransportServiceTest bootstrap directly.
    implementation(project(":network"))
    implementation(project(":network-demo"))
//    implementation("com.tencent.kuiklybase:network:0.0.3")
}
