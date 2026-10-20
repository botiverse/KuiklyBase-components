package com.tencent.tmm.kmmgradle.generator.apple.task

import com.tencent.tmm.kmmgradle.utils.klibs
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink
import com.tencent.tmm.kmmgradle.utils.klibResourcesDir
import java.io.File

internal abstract class CopyResourcesFromKLibsTask : DefaultTask() {

    protected fun copyKlibsResourcesIntoFramework(linkTask: KotlinNativeLink) {
        CopyResourcesFromKLibsTask.copyKlibsResourcesIntoFramework(linkTask)
    }

    protected open fun copyResourcesFromLibraries(
        linkTask: KotlinNativeLink,
        project: Project,
        outputDir: File
    ) {
        CopyResourcesFromKLibsTask.copyResourcesFromLibraries(linkTask, project, outputDir)
    }

    companion object {
        internal fun copyKlibsResourcesIntoFramework(linkTask: KotlinNativeLink) {
            val project = linkTask.project
            val framework = linkTask.binary as Framework

            copyResourcesFromLibraries(
                linkTask = linkTask,
                project = project,
                outputDir = framework.outputFile
            )
        }

        internal fun copyResourcesFromLibraries(
            linkTask: KotlinNativeLink,
            project: Project,
            outputDir: File
        ) {
            linkTask.klibs
                .filter { it.extension == "klib" }
                .filter { it.exists() }
                .forEach { inputFile ->
                    println("copy resources from $inputFile into $outputDir")
                    val resourcesDir = inputFile.klibResourcesDir()

                    try {
                        resourcesDir.copyRecursively(
                            target = outputDir,
                            overwrite = true
                        )
                    } catch (@Suppress("SwallowedException") exc: NoSuchFileException) {
                        project.logger.info("resources in $inputFile not found")
                    } catch (@Suppress("SwallowedException") exc: java.nio.file.NoSuchFileException) {
                        project.logger.info("resources in $inputFile not found (empty lib)")
                    }
                }
        }
    }
}