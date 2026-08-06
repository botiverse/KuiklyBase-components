@file:OptIn(org.jetbrains.kotlin.gradle.InternalKotlinGradlePluginApi::class)

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile
import kotlinx.atomicfu.plugin.gradle.AtomicFUPluginExtension

plugins {
    kotlin("multiplatform")
    `maven-publish`
}

apply(plugin = "kotlinx-atomicfu")

group = "org.jetbrains.kotlinx"
version = providers.gradleProperty("coroutinesVersion").get()

val sourceRoot = providers.gradleProperty("coroutinesSourceDir")
    .map { file(it).canonicalFile }
    .get()
val coreRoot = sourceRoot.resolve("kotlinx-coroutines-core")
check(coreRoot.resolve("common/src").isDirectory) {
    "coroutinesSourceDir is not a prepared kotlinx.coroutines checkout: $sourceRoot"
}

val carrierSha = providers.gradleProperty("forkCarrierSha").get()
val carrierRepository = providers.gradleProperty("carrierRepository").get()
val upstreamRepository = providers.gradleProperty("coroutinesUpstreamRepository").get()
val upstreamSha = providers.gradleProperty("coroutinesUpstreamSha").get()
val patchSha256 = providers.gradleProperty("coroutinesPatchSha256").get()
val atomicfuVersion = providers.gradleProperty("atomicfuVersion").get()

val orderedKotlinSources = listOf(
    coreRoot.resolve("common/src"),
    coreRoot.resolve("concurrent/src"),
    coreRoot.resolve("native/src"),
    coreRoot.resolve("nativeOhos/src"),
).flatMap { sourceDirectory ->
    check(sourceDirectory.isDirectory) {
        "Coroutines Kotlin source directory is missing: $sourceDirectory"
    }
    sourceDirectory.walkTopDown()
        .filter { source -> source.isFile && source.extension == "kt" }
        .map { source -> source.canonicalFile }
        .toList()
}.sortedBy { source -> source.relativeTo(sourceRoot).invariantSeparatorsPath }
check(orderedKotlinSources.isNotEmpty()) {
    "Coroutines ordered Kotlin source list is empty"
}
check(orderedKotlinSources.distinct().size == orderedKotlinSources.size) {
    "Coroutines ordered Kotlin source list contains duplicate files"
}

extensions.configure<AtomicFUPluginExtension>("atomicfu") {
    dependenciesVersion = atomicfuVersion
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    ohosArm64 {
        compilations.forEach {
            it.compilerOptions.options.optIn.addAll(
                "kotlinx.cinterop.ExperimentalForeignApi",
                "kotlinx.cinterop.UnsafeNumber",
                "kotlin.experimental.ExperimentalNativeApi",
                "kotlin.experimental.ExperimentalTypeInference",
                "kotlin.ExperimentalMultiplatform",
                "kotlinx.coroutines.DelicateCoroutinesApi",
                "kotlinx.coroutines.ExperimentalCoroutinesApi",
                "kotlinx.coroutines.ObsoleteCoroutinesApi",
                "kotlinx.coroutines.InternalCoroutinesApi",
                "kotlinx.coroutines.FlowPreview",
            )
            it.compilerOptions.options.freeCompilerArgs.addAll(
                "-progressive",
                "-Xexpect-actual-classes",
            )
        }
        compilations.getByName("main") {
            cinterops.create("interop") {
                definitionFile.set(coreRoot.resolve("src/nativeInterop/cinterop/interop.def"))
                includeDirs(coreRoot.resolve("src/nativeInterop/cinterop/cpp/include"))
                extraOpts("-libraryPath", coreRoot.resolve("libs").absolutePath)
            }
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(coreRoot.resolve("common/src"))
            dependencies {
                api("org.jetbrains.kotlinx:atomicfu:$atomicfuVersion")
            }
        }
        val concurrentMain by creating {
            dependsOn(commonMain.get())
            kotlin.srcDir(coreRoot.resolve("concurrent/src"))
        }
        val nativeMain by creating {
            dependsOn(concurrentMain)
            kotlin.srcDir(coreRoot.resolve("native/src"))
        }
        val nativeOhosMain by creating {
            dependsOn(nativeMain)
            kotlin.srcDir(coreRoot.resolve("nativeOhos/src"))
        }
        val ohosArm64Main by getting {
            dependsOn(nativeOhosMain)
        }
    }
}

afterEvaluate {
    tasks.withType<KotlinNativeCompile>().configureEach {
        val orderedFragments = multiplatformStructure.fragments.get().map { fragment ->
            val discoveredSources = fragment.sources.asFileTree.files
                .map { source -> source.canonicalFile }
                .toSet()
            val fragmentSources = orderedKotlinSources.filter(discoveredSources::contains)
            check(fragmentSources.toSet() == discoveredSources) {
                "Coroutines fragment ${fragment.fragmentName} contains an unexpected source"
            }
            fragment.copy(sources = files(fragmentSources))
        }.sortedBy { fragment -> fragment.fragmentName }
        check(orderedFragments.flatMap { fragment -> fragment.sources.files.toList() } == orderedKotlinSources) {
            "Coroutines fragment source graph does not exactly cover the ordered Kotlin source list"
        }
        multiplatformStructure.fragments.set(orderedFragments)
        multiplatformStructure.fragments.disallowChanges()

        val orderedRefinesEdges = multiplatformStructure.refinesEdges.get().sortedWith(
            compareBy({ edge -> edge.fromFragmentName }, { edge -> edge.toFragmentName }),
        )
        multiplatformStructure.refinesEdges.set(orderedRefinesEdges.toCollection(linkedSetOf()))
        multiplatformStructure.refinesEdges.disallowChanges()

        // The pinned KGP's setSource method appends to its private sourceFiles
        // collection. Replace the public collection consumed by the compiler.
        val compilerSources = sources as? ConfigurableFileCollection
            ?: error("Kotlin/Native sources are not configurable under the pinned Kotlin Gradle plugin")
        compilerSources.setFrom(orderedKotlinSources)
        compilerSources.disallowChanges()
        doFirst {
            val actualSources = sources.asFileTree.toList()
            check(actualSources == orderedKotlinSources) {
                val expected = orderedKotlinSources.joinToString("\n") { it.absolutePath }
                val actual = actualSources.joinToString("\n") { it.absolutePath }
                "Coroutines Kotlin/Native source order drifted. Expected:\n$expected\nActual:\n$actual"
            }
            val actualFragmentSources = multiplatformStructure.fragments.get()
                .flatMap { fragment -> fragment.sources.files.toList() }
            check(actualFragmentSources == orderedKotlinSources) {
                "Coroutines Kotlin/Native fragment source order drifted"
            }
            val actualRefinesEdges = multiplatformStructure.refinesEdges.get().toList()
            check(actualRefinesEdges == orderedRefinesEdges) {
                "Coroutines Kotlin/Native fragment refinement order drifted"
            }
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("kotlinx-coroutines-core")
            description.set("Coroutines support libraries for Kotlin with OpenHarmony support")
            url.set("https://github.com/Kotlin/kotlinx.coroutines")
            licenses {
                license {
                    name.set("The Apache Software License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("JetBrains")
                    name.set("JetBrains Team")
                    organization.set("JetBrains")
                    organizationUrl.set("https://www.jetbrains.com")
                }
            }
            scm {
                url.set(carrierRepository)
                tag.set(carrierSha)
            }
            properties.put("dev.raft.carrierRepository", carrierRepository)
            properties.put("dev.raft.carrierSha", carrierSha)
            properties.put("dev.raft.upstreamRepository", upstreamRepository)
            properties.put("dev.raft.upstreamSha", upstreamSha)
            properties.put("dev.raft.patchSha256", patchSha256)
        }
    }
}
