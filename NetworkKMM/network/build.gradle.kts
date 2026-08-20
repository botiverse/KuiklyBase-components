import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.`maven-publish`
import org.gradle.kotlin.dsl.signing
import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.util.*

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.native.cocoapods")
    id("com.android.library")
    `maven-publish`
    signing
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    val iosCurlSpikeEnabled = providers.gradleProperty("iosCurlSpike").isPresent
    val iosCurlDefinition = project.file("src/iosMain/c_interop/ios_curl.def")
    val iosCurlFrameworkRoot = project.file("libs/ios/NetworkKMMCurl.xcframework")

    fun KotlinNativeTarget.configureIosCurlInterop() {
        val slice = when (name) {
            "iosArm64" -> "ios-arm64"
            "iosX64", "iosSimulatorArm64" -> "ios-arm64_x86_64-simulator"
            else -> error("Unsupported iOS curl target: $name")
        }
        val sliceRoot = iosCurlFrameworkRoot.resolve(slice)
        compilations.getByName("main").cinterops.create("iosCurl") {
            definitionFile.set(iosCurlDefinition)
            includeDirs(project.file("../ohosApp/pbcurlwrapper/src/main/cpp/wrapper/include"))
            extraOpts("-libraryPath", sliceRoot.absolutePath)
        }
    }

    // 使用默认层级结构模板
    KotlinHierarchyTemplate.default

    // Android平台
    androidTarget {
        // Kotlin 2.2 removed the deprecated kotlinOptions DSL.
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        }

        // 添加以下配置，将Android平台打包到组件产物中
        publishAllLibraryVariants()
        publishLibraryVariantsGroupedByFlavor = true
        publishLibraryVariants("release", "debug")
    }

    // iOS平台
    iosX64 {
        configureIosCurlInterop()
        if (iosCurlSpikeEnabled) {
            val main by compilations.getting
            main.cinterops.create("iosCurlSpike") {
                definitionFile.set(project.file("src/iosCurlSpikeMain/c_interop/ios_curl_spike.def"))
                includeDirs("${project.rootDir}/ohosApp/pbcurlwrapper/src/main/cpp/wrapper/include")
            }
        }
    }
    iosArm64 {
        configureIosCurlInterop()
    }
    iosSimulatorArm64 {
        configureIosCurlInterop()
        if (iosCurlSpikeEnabled) {
            val main by compilations.getting
            main.cinterops.create("iosCurlSpike") {
                definitionFile.set(project.file("src/iosCurlSpikeMain/c_interop/ios_curl_spike.def"))
                includeDirs("${project.rootDir}/ohosApp/pbcurlwrapper/src/main/cpp/wrapper/include")
            }
        }
    }
    // Darwin behavior-test lane (-PdarwinBehaviorTests): a macosArm64 target that
    // runs the SAME iosMain transport (ktor Darwin/NSURLSession) natively on a
    // macOS CI runner against a local server. iOS was the only platform whose
    // engine semantics shipped untested (raft.9 lesson); this lane closes that.
    // Conditional so publications and consumer dependency resolution are
    // untouched for everyone else.
    if (providers.gradleProperty("darwinBehaviorTests").isPresent) {
        macosArm64()
    }
    cocoapods {
        name = "network"
        summary = "Some description for the Shared Module"
        homepage = "Link to the Shared Module homepage"
        version = "1.0"
        ios.deploymentTarget = "12.0"
        if (providers.gradleProperty("darwinBehaviorTests").isPresent) {
            osx.deploymentTarget = "11.0"
        }
        podfile = project.file("../iosApp/Podfile")
        framework {
            baseName = "network"
            // 如果依赖的 pod 组件是静态库，那么建议打开下面注释
            // 同时需要去除 iosApp 中对应脚本：iosApp Target -> Build Phases -> Run Script
//            isStatic = true
        }
    }

    // ohosArm64 lives in the OHOS build tree only (build.ohos.gradle.kts,
    // selected via -c settings.ohos.gradle.kts) — task #18 dual-tree split.

    sourceSets {
        val androidMain by getting {
            dependencies {
                implementation(libs.ktor.ktor.client.core)
                // OkHttp 引擎（默认）：fastFallback 提供 RFC 8305 Happy Eyeballs
                // 并行竞速，坏地址族不再串行吃掉整个连接预算。
                implementation(libs.ktor.client.okhttp)
                // 强制 OkHttp 5.x：fastFallback API 自 5.0 起才存在。
                implementation(libs.okhttp)
                // 旧 HttpURLConnection 引擎保留作运行时回退开关。
                implementation(libs.ktor.client.android)
            }
        }

        val commonMain by getting {
            dependencies {
                // 协程
                implementation(libs.kotlinx.coroutines.core)
                // 原子操作
                implementation(libs.atomicfu)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val androidUnitTest by getting {
            dependencies {
                implementation(libs.mockwebserver)
            }
        }

        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosX64Test by getting
        val iosArm64Test by getting
        val iosSimulatorArm64Test by getting
        if (iosCurlSpikeEnabled) {
            iosX64Main.kotlin.srcDir("src/iosCurlSpikeMain/kotlin")
            iosSimulatorArm64Main.kotlin.srcDir("src/iosCurlSpikeMain/kotlin")
        }
        val iosMain by creating {
            dependsOn(commonMain)
            iosX64Main.dependsOn(this)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
            dependencies {
                implementation(libs.ktor.ktor.client.core)
                // iOS 平台的 Ktor 引擎
                implementation(libs.ktor.client.darwin)
            }
        }
        val iosTest by creating {
            dependsOn(commonTest)
            iosX64Test.dependsOn(this)
            iosArm64Test.dependsOn(this)
            iosSimulatorArm64Test.dependsOn(this)
        }


        if (providers.gradleProperty("darwinBehaviorTests").isPresent) {
            val macosArm64Main by getting {
                kotlin.srcDir("src/iosMain/kotlin")
                kotlin.srcDir("src/darwinBehaviorMain/kotlin")
                kotlin.exclude("com/tencent/kmm/network/export/VBTransportIosCurl.kt")
                kotlin.exclude("com/tencent/kmm/network/export/NetworkCurlNativeStatus.ios.kt")
                kotlin.exclude("com/tencent/kmm/network/internal/platform/IosCurlNativeBridge.kt")
                kotlin.exclude("com/tencent/kmm/network/internal/platform/IosCurlNetworkEngine.kt")
                kotlin.exclude("com/tencent/kmm/network/internal/platform/PlatformNetworkEngines.ios.kt")
                dependencies {
                    implementation(libs.ktor.ktor.client.core)
                    implementation(libs.ktor.client.darwin)
                }
            }
        }

    }
}

android {
    namespace = "com.tencent.tmm.networkkmm"
    // 36: okhttp-android 5.4.0 (stable, task #18 unpin) requires consumers
    // to compile against API 36+ (its androidx.startup pull required 34+).
    compileSdk = 36
    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets {
        // Android production runs OkHttp (artin decision A, 2026-07-10):
        // per-ABI libnetworkkmmcurl.so remains committed and CI-gated under
        // libs/android, but is not packaged into the default Android AAR.
        // The CURL delegate fail-closes to Ktor when absent; a dedicated
        // opt-in coordinate is a future decision.
        // Keep the committed curl artifact covered by the instrumentation
        // runtime gate without leaking it into the published Android AAR.
        getByName("androidTest") {
            jniLibs.srcDir("libs/android")
        }
    }
    testOptions {
        // OkHttp's static init logs through android.util.Log; JVM unit tests
        // get the unmocked stub ("not mocked" RuntimeException) without this.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    add("androidTestImplementation", "androidx.test:runner:1.6.2")
    add("androidTestImplementation", "androidx.test.ext:junit:1.2.1")
}

// Stub secrets to let the project sync and build without the publication values set up
extra["signing.keyId"] = null
extra["signing.password"] = null
extra["signing.secretKeyRingFile"] = null
extra["ossrhUsername"] = null
extra["ossrhPassword"] = null
extra["githubPackagesOwner"] = null
extra["githubPackagesRepository"] = null
extra["githubPackagesUsername"] = null
extra["githubPackagesToken"] = null

fun getExtraString(name: String) = try {
    extra[name]?.toString()
} catch (ignore: Exception) {
    null
}

fun String?.validPublishValue(): String? = takeIf { !it.isNullOrBlank() && it != "null" }

fun getOptionalPublishProperty(name: String, vararg envNames: String): String? {
    findProperty(name)?.toString().validPublishValue()?.let { return it }
    getExtraString(name).validPublishValue()?.let { return it }
    return envNames.firstNotNullOfOrNull { System.getenv(it).validPublishValue() }
}

fun getRequiredPublishProperty(name: String, vararg envNames: String): String {
    return getOptionalPublishProperty(name, *envNames)
        ?: error("Missing publish property: $name")
}

// Grabbing secrets from local.properties file or from environment variables, which could be used on CI
val secretPropsFile = project.rootProject.file("local.properties")
if (secretPropsFile.exists()) {
    secretPropsFile.reader().use {
        Properties().apply {
            load(it)
        }
    }.onEach { (name, value) ->
        extra[name.toString()] = value
    }
} else {
    extra["signing.keyId"] = System.getenv("SIGNING_KEY_ID")
    extra["signing.password"] = System.getenv("SIGNING_PASSWORD")
    extra["signing.secretKeyRingFile"] = System.getenv("SIGNING_SECRET_KEY_RING_FILE")
    extra["ossrhUsername"] = System.getenv("OSSRH_USERNAME")
    extra["ossrhPassword"] = System.getenv("OSSRH_PASSWORD")
}

val publishFile = project.rootProject.file("gradle.properties")
if (publishFile.exists()) {
    publishFile.reader().use {
        Properties().apply {
            load(it)
        }
    }.onEach { (name, value) ->
        extra[name.toString()] = value
    }
}

val publishGroupID = getRequiredPublishProperty("gruopID", "MAVEN_GROUP_ID")
val publishVersion = getRequiredPublishProperty("mavenVersion", "MAVEN_VERSION")
val publishArtifactID = getRequiredPublishProperty("artifactID", "MAVEN_ARTIFACT_ID")
val githubRepositoryEnv = System.getenv("GITHUB_REPOSITORY")
val githubPackagesOwner = (
    getOptionalPublishProperty("githubPackagesOwner", "GITHUB_PACKAGES_OWNER")
        ?: githubRepositoryEnv?.substringBefore("/")
        ?: "bytemain"
    ).lowercase(Locale.US)
val githubPackagesRepository = getOptionalPublishProperty(
    "githubPackagesRepository",
    "GITHUB_PACKAGES_REPOSITORY",
) ?: githubRepositoryEnv?.substringAfter("/") ?: "KuiklyBase-components"
val githubPackagesUsername = getOptionalPublishProperty(
    "githubPackagesUsername",
    "GITHUB_PACKAGES_USERNAME",
    "GITHUB_ACTOR",
) ?: getOptionalPublishProperty("gpr.user")
val githubPackagesToken = getOptionalPublishProperty(
    "githubPackagesToken",
    "GITHUB_PACKAGES_TOKEN",
    "GITHUB_TOKEN",
    "GH_TOKEN",
) ?: getOptionalPublishProperty("gpr.key")
val signingKeyId = getOptionalPublishProperty("signing.keyId", "SIGNING_KEY_ID")
val signingPassword = getOptionalPublishProperty("signing.password", "SIGNING_PASSWORD")
val signingSecretKeyRingFile = getOptionalPublishProperty(
    "signing.secretKeyRingFile",
    "SIGNING_SECRET_KEY_RING_FILE",
)
val shouldSignPublications = listOf(
    signingKeyId,
    signingPassword,
    signingSecretKeyRingFile,
).all { !it.isNullOrBlank() }

val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

publishing {
    // Configure maven central repository
    repositories {
        maven {
            name = "maven"
//            val releasesRepoUrl = "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
//            val snapshotsRepoUrl = "https://s01.oss.sonatype.org/content/repositories/snapshots/"
            val releasesRepoUrl = uri("https://oss.sonatype.org/service/local/staging/deploy/maven2")
            val snapshotsRepoUrl = uri("https://oss.sonatype.org/content/repositories/snapshots")
            url = if (publishVersion.endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
            credentials {
                username = getExtraString("ossrhUsername")
                password = getExtraString("ossrhPassword")
            }
        }
        maven {
            name = "githubPackages"
            url = uri("https://maven.pkg.github.com/$githubPackagesOwner/$githubPackagesRepository")
            credentials {
                username = githubPackagesUsername.orEmpty()
                password = githubPackagesToken.orEmpty()
            }
        }
    }

    // Configure all publications
    publications.withType<MavenPublication> {
        // Stub javadoc.jar artifact
//        artifact(javadocJar.get())

        // Provide artifacts information requited by Maven Central
        pom {
            name.set(publishArtifactID)
            description.set("Tencent Http Service Library.")
            url.set("https://github.com/bytemain/KuiklyBase-components.git")

            licenses {
                license {
                    name.set("Apache License 2.0")
                    url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("bytemain")
                    name.set("bytemain")
                    organization.set("bytemain")
                    organizationUrl.set("https://github.com/bytemain")
                }
            }
            scm {
                url.set("https://github.com/bytemain/KuiklyBase-components")
            }
        }
    }
}

extensions.configure<SigningExtension> {
    if (shouldSignPublications) {
        sign(publishing.publications)
    }
}


apply(from = rootProject.file("gradle/user-agent-version-gate.gradle.kts"))
