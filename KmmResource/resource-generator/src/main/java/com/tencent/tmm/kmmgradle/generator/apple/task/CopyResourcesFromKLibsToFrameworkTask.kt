/*
 * Copyright 2023 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */

package com.tencent.tmm.kmmgradle.generator.apple.task

import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink
import java.io.File

internal open class CopyResourcesFromKLibsToFrameworkTask : CopyResourcesFromKLibsTask() {

    @get:Internal
    lateinit var linkTask: KotlinNativeLink

    @TaskAction
    fun execute() {
        println("do action CopyResourcesFromKLibsToFrameworkAction")

        val framework: Framework = linkTask.binary as Framework
        val outputDir: File = framework.outputFile

        // Drop any stale extraction before re-copying.
        File(outputDir, RESOURCES_SUBDIR).takeIf { it.exists() }?.deleteRecursively()

        copyResourcesFromLibraries(
            linkTask = linkTask,
            project = linkTask.project,
            outputDir = outputDir
        )

        // `copyResourcesFromLibraries` copies the klib's `resources/` contents,
        // so the packed bundles land under
        // `<framework>/tmm-resources-apple/<name>.bundle`. But
        // `copyFrameworkResourcesToApp` (and Xcode framework embedding) only look
        // for `*.bundle` directly under the framework, so flatten them up to the
        // framework root and drop the now-empty subdir.
        val nested = File(outputDir, RESOURCES_SUBDIR)
        if (nested.isDirectory) {
            nested.listFiles { file -> file.extension == "bundle" }?.forEach { bundle ->
                val dest = File(outputDir, bundle.name)
                if (dest.exists()) dest.deleteRecursively()
                bundle.copyRecursively(dest, overwrite = true)
            }
            nested.deleteRecursively()
        }
    }

    companion object {
        const val TASK_NAME = "copyResourcesFromKLibsToFrameworkTask"
        private const val RESOURCES_SUBDIR = "tmm-resources-apple"
    }
}
