/*
 * Copyright 2024 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */

package com.tencent.tmm.kmmgradle.generator.apple.task

import com.tencent.tmm.kmmgradle.generator.apple.LoadableBundle
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink
import java.io.File

/**
 * Writes THIS module's own generated MR resources ([resourcesGenerationDir]) as
 * a `<bundleName>.bundle` directly into the linked framework's output directory.
 *
 * Why this exists (instead of the klib pack/extract path): under recent KGP the
 * klib-based path does not deliver the owning module's resources into a CocoaPods
 * static framework —
 *  - `KotlinNativeCompile` does not run ordinary Gradle `doLast` actions, so
 *    `PackResourcesToKLibAction` (attached via `compileKotlinTask.doLast`) never
 *    executes and the module's klib is never packed; and
 *  - the module's own compile output is an *unpacked klib directory*, which the
 *    `it.extension == "klib"` filter in the copy tasks silently skips.
 * The result: the module's MR `.bundle` never reaches the framework and the first
 * `MR.*` access throws `FileFailedToInitializeException` at runtime on iOS.
 *
 * This task bypasses the klib entirely for the owning module's resources: it
 * builds the bundle straight from [resourcesGenerationDir] and drops it at the
 * framework root, where `copyFrameworkResourcesToApp` (and Xcode framework
 * embedding) can find it. Dependency-library resources keep flowing through the
 * existing klib copy tasks (their artifacts are real `.klib` files). It is wired
 * as a `finalizedBy` of the link task — link finalizers DO run (unlike
 * `KotlinNativeCompile.doLast`).
 */
internal open class PackModuleResourcesToFrameworkTask : DefaultTask() {

    @get:Internal
    lateinit var linkTask: KotlinNativeLink

    @get:Internal
    lateinit var resourcesGenerationDir: File

    @get:Internal
    lateinit var assetsDirectory: File

    @get:Internal
    lateinit var bundleName: String

    @get:Internal
    lateinit var bundleIdentifier: String

    @get:Internal
    var baseLocalizationRegion: String? = null

    @TaskAction
    fun execute() {
        if (resourcesGenerationDir.exists().not()) {
            logger.info("kmmresource: no module resources at $resourcesGenerationDir, skipping")
            return
        }

        val framework: Framework = linkTask.binary as Framework
        val outputDir: File = framework.outputFile

        // Build the bundle in a private temp dir so we never touch the framework's
        // `tmm-resources-apple/` staging area (owned by the dependency copy tasks).
        val stagingDir = File(temporaryDir, "moduleResources").apply {
            deleteRecursively()
            mkdirs()
        }

        val loadableBundle = LoadableBundle(
            directory = stagingDir,
            bundleName = bundleName,
            developmentRegion = baseLocalizationRegion,
            identifier = bundleIdentifier
        )
        loadableBundle.write()

        // Best-effort image-asset compilation, mirroring PackResourcesToKLibAction.
        // Harmless when there is no Assets.xcassets (strings-only resources): the
        // subsequent copy still populates the bundle.
        val assetsXcassets = File(assetsDirectory, "Assets.xcassets")
        if (assetsXcassets.exists()) {
            runCatching {
                val process = Runtime.getRuntime().exec(
                    arrayOf(
                        "xcrun", "actool", "Assets.xcassets",
                        "--compile", loadableBundle.resourcesDir.absolutePath,
                        "--platform", "iphoneos",
                        "--minimum-deployment-target", "12.0"
                    ),
                    emptyArray(),
                    assetsDirectory
                )
                val errors = process.errorStream.bufferedReader().readText()
                val output = process.inputStream.bufferedReader().readText()
                if (process.waitFor() != 0) {
                    logger.warn("kmmresource: actool failed for $bundleName: $errors")
                } else {
                    logger.info("kmmresource: actool compiled assets for $bundleName $output")
                }
            }.onFailure { logger.warn("kmmresource: actool not run for $bundleName: ${it.message}") }
        }

        resourcesGenerationDir.copyRecursively(loadableBundle.resourcesDir, overwrite = true)

        // Place `<bundleName>.bundle` directly at the framework root.
        val bundleDir: File = loadableBundle.bundleDir
        val dest = File(outputDir, bundleDir.name)
        if (dest.exists()) {
            dest.deleteRecursively()
        }
        bundleDir.copyRecursively(dest, overwrite = true)
        logger.info("kmmresource: wrote module bundle $dest")

        stagingDir.deleteRecursively()
    }

    companion object {
        const val TASK_NAME = "packModuleResourcesToFrameworkTask"
    }
}
