/*
 * Copyright 2023 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */

package com.tencent.tmm.kmmgradle.generator.apple.action

import com.tencent.tmm.kmmgradle.generator.MRGenerator
import com.tencent.tmm.kmmgradle.generator.apple.LoadableBundle
import com.tencent.tmm.kmmgradle.generator.apple.bundleIdentifierProvider
import com.tencent.tmm.kmmgradle.utils.unzipTo
import org.gradle.api.Action
import org.gradle.api.Task
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile
import com.tencent.tmm.kmmgradle.utils.zipDirectoryTo
import java.io.File
import java.util.Properties

internal class PackResourcesToKLibAction(
    private val mrSettings: MRGenerator.MRSettings,
    private val baseLocalizationRegion: String?,
    private val bundleIdentifierProvider: String,
    private val assetsDirectoryProvider: File,
    private val resourcesGenerationDirProvider: File,
) : Action<Task> {
    override fun execute(task: Task) {
        task as KotlinNativeCompile

        val klibFile = task.outputFile.get()
        val repackDir = File(klibFile.parent, klibFile.nameWithoutExtension)
        val defaultDir = File(repackDir, "default")
        val resRepackDir = File(defaultDir, "resources")
        val assetsDirectory =
            File(assetsDirectoryProvider.parentFile.path + File.separatorChar + "res")
        val assetsXcassets = File(assetsDirectory.path + File.separatorChar + "Assets.xcassets")
        val resourcesGenerationDir: File = resourcesGenerationDirProvider
        val assetsDirectoryProvider: File = assetsDirectoryProvider

        println("resRepackDir ${resRepackDir.path}")
        println("resourcesGenerationDir ${resourcesGenerationDir.path}")

        task.logger.info("Adding resources to klib file `{}`", klibFile)
        unzipTo(zipFile = klibFile, outputDirectory = repackDir)

        val manifestFile = File(defaultDir, "manifest")
        val manifest = Properties()
        manifest.load(manifestFile.inputStream())

        val uniqueName = mrSettings.bundleIdentifierProvider()

        val loadableBundle = LoadableBundle(
            directory = resRepackDir,
            bundleName = uniqueName,
            developmentRegion = baseLocalizationRegion,
            identifier = bundleIdentifierProvider
        )
        loadableBundle.write()

        val process: Process = Runtime.getRuntime().exec(
            buildString {
                append("xcrun actool ")
                append("Assets.xcassets")
                append(" --compile . --platform iphoneos --minimum-deployment-target ")
                append("12.0")
            },
            emptyArray(),
            assetsDirectory
        )
        val errors: String = process.errorStream.bufferedReader().readText()
        val input: String = process.inputStream.bufferedReader().readText()
        val result: Int = process.waitFor()
        if (result != 0) {
            task.logger.error("can't compile assets - $result")
            println("can't compile assets - $result")
            task.logger.info(input)
            task.logger.error(errors)
        } else {
            task.logger.info("assets compiled")
            println("assets compiled")
            println(errors)
            println(input)
            assetsXcassets.deleteRecursively()
            assetsDirectoryProvider.deleteRecursively()
        }

        resourcesGenerationDir.copyRecursively(
            loadableBundle.resourcesDir,
            overwrite = true
        )

        klibFile.delete()
        repackDir.zipDirectoryTo(klibFile)

        repackDir.deleteRecursively()
    }
}
