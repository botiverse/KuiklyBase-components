plugins {
    id("com.android.application")
}

val nativeRoot = providers.gradleProperty("androidCurlSpikeNativeRoot")
val assetsRoot = providers.gradleProperty("androidCurlSpikeAssetsRoot")

android {
    namespace = "com.tencent.networkkmm.curlspike"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tencent.networkkmm.curlspike"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    sourceSets.getByName("main") {
        jniLibs.srcDir(nativeRoot)
        assets.srcDir(assetsRoot)
    }
}
