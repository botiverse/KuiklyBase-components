/*
 * Copyright 2022 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */

package com.tencent.tmm.kmmgradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.konan.properties.Properties
import com.tencent.tmm.kmmgradle.utils.klibResourcesDir
import java.io.File
import java.io.FileFilter
import java.io.FileInputStream

abstract class CopyExecutableResourcesToApp : DefaultTask() {

    @get:Internal
    abstract val klibs: ConfigurableFileCollection

    init {
        group = "tmm-resources"
    }

    @TaskAction
    fun copyResources() {
        val rootDir = project.rootDir
        // 构建 local.properties 文件的路径
        val localPropertiesFile = File(rootDir, "local.properties")
        val localProperties = Properties()
        localProperties.load(FileInputStream(localPropertiesFile))

        val buildProductsDir =
            File(localProperties.getProperty("tmm.resources.BUILT_PRODUCTS_DIR") as String)
        val contentsFolderPath =
            localProperties.getProperty("tmm.resources.CONTENTS_FOLDER_PATH") as String

        val outputDir = File(buildProductsDir, contentsFolderPath)

        klibs
            .filter { library -> library.extension == "klib" }
            .filter(File::exists)
            .forEach { inputFile ->
                // extracting bundles
                inputFile.klibResourcesDir()
                    .listFiles(FileFilter { it.extension == "bundle" })
                    // copying bundles to app
                    ?.forEach {
                        logger.info("${it.absolutePath} copying to $outputDir")
                        it.copyRecursively(target = File(outputDir, it.name), overwrite = true)
                    }
            }
    }
}
