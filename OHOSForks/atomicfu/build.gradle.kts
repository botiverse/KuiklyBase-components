@file:OptIn(org.jetbrains.kotlin.gradle.InternalKotlinGradlePluginApi::class)

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile

plugins {
    kotlin("multiplatform")
    `maven-publish`
}

group = "org.jetbrains.kotlinx"
version = providers.gradleProperty("atomicfuVersion").get()

val sourceRoot = providers.gradleProperty("atomicfuSourceDir")
    .map { file(it).canonicalFile }
    .get()
check(sourceRoot.resolve("atomicfu/src/commonMain/kotlin").isDirectory) {
    "atomicfuSourceDir is not a prepared kotlinx.atomicfu checkout: $sourceRoot"
}

val carrierSha = providers.gradleProperty("forkCarrierSha").get()
val carrierRepository = providers.gradleProperty("carrierRepository").get()
val upstreamRepository = providers.gradleProperty("atomicfuUpstreamRepository").get()
val upstreamSha = providers.gradleProperty("atomicfuUpstreamSha").get()
val patchSha256 = providers.gradleProperty("atomicfuPatchSha256").get()

val orderedKotlinSources = listOf(
    sourceRoot.resolve("atomicfu/src/commonMain/kotlin"),
    sourceRoot.resolve("atomicfu/src/nativeMain/kotlin"),
    sourceRoot.resolve("atomicfu/src/ohosArm64Main/kotlin"),
).flatMap { sourceDirectory ->
    check(sourceDirectory.isDirectory) {
        "AtomicFU Kotlin source directory is missing: $sourceDirectory"
    }
    sourceDirectory.walkTopDown()
        .filter { source -> source.isFile && source.extension == "kt" }
        .map { source -> source.canonicalFile }
        .toList()
}.sortedBy { source -> source.relativeTo(sourceRoot).invariantSeparatorsPath }
check(orderedKotlinSources.isNotEmpty()) {
    "AtomicFU ordered Kotlin source list is empty"
}
check(orderedKotlinSources.distinct().size == orderedKotlinSources.size) {
    "AtomicFU ordered Kotlin source list contains duplicate files"
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    ohosArm64 {
        compilations.forEach {
            it.compilerOptions.options.optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
            it.compilerOptions.options.freeCompilerArgs.add("-Xexpect-actual-classes")
        }
        compilations.getByName("main") {
            cinterops.create("interop") {
                definitionFile.set(sourceRoot.resolve("atomicfu/src/nativeInterop/cinterop/interop.def"))
            }
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(sourceRoot.resolve("atomicfu/src/commonMain/kotlin"))
        }
        val nativeMain by creating {
            dependsOn(commonMain.get())
            kotlin.srcDir(sourceRoot.resolve("atomicfu/src/nativeMain/kotlin"))
        }
        val ohosArm64Main by getting {
            dependsOn(nativeMain)
            kotlin.srcDir(sourceRoot.resolve("atomicfu/src/ohosArm64Main/kotlin"))
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
                "AtomicFU fragment ${fragment.fragmentName} contains an unexpected source"
            }
            fragment.copy(sources = files(fragmentSources))
        }.sortedBy { fragment -> fragment.fragmentName }
        check(orderedFragments.flatMap { fragment -> fragment.sources.files.toList() } == orderedKotlinSources) {
            "AtomicFU fragment source graph does not exactly cover the ordered Kotlin source list"
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
                "AtomicFU Kotlin/Native source order drifted. Expected:\n$expected\nActual:\n$actual"
            }
            val actualFragmentSources = multiplatformStructure.fragments.get()
                .flatMap { fragment -> fragment.sources.files.toList() }
            check(actualFragmentSources == orderedKotlinSources) {
                "AtomicFU Kotlin/Native fragment source order drifted"
            }
            val actualRefinesEdges = multiplatformStructure.refinesEdges.get().toList()
            check(actualRefinesEdges == orderedRefinesEdges) {
                "AtomicFU Kotlin/Native fragment refinement order drifted"
            }
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("kotlinx.atomicfu")
            description.set("AtomicFU utilities with OpenHarmony support")
            url.set("https://github.com/Kotlin/kotlinx.atomicfu")
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
