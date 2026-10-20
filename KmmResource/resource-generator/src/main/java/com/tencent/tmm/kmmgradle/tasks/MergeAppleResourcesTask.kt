package com.tencent.tmm.kmmgradle.tasks

import com.tencent.tmm.kmmgradle.utils.copyDirectory
import com.tencent.tmm.kmmgradle.utils.klibs
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink
import com.tencent.tmm.kmmgradle.utils.klibResourcesDir
import java.io.File

open class MergeAppleResourcesTask : DefaultTask() {

    @get:Internal
    internal lateinit var resourcesDir: File

    @get:Internal
    internal lateinit var linkTask: KotlinNativeLink

    @TaskAction
    fun doAction() {
        if (linkTask == null) {
            return
        }
        if (resourcesDir.exists().not()) {
            return
        }
        val mergeResDir = File(resourcesDir.parentFile, MERGE_RES_DIR).apply {
            if (exists().not()) {
                mkdirs()
            }
        }
        linkTask.klibs.forEach { dependency ->
            copyResourcesFromLibraries(
                inputFile = dependency,
                project = project,
                outputDir = mergeResDir
            )
        }
    }

    private fun copyResourcesFromLibraries(
        inputFile: File,
        project: Project,
        outputDir: File
    ) {
        if (inputFile.extension != "klib") return
        if (inputFile.exists().not()) return

        project.logger.info("copy resources from $inputFile into $outputDir")

        try {
            val resDir = File(inputFile.klibResourcesDir(), "tmm-resources-apple")
            if (resDir.exists()) {
                resDir.copyDirectory(outputDir)
            }
        } catch (@Suppress("SwallowedException") exc: kotlin.io.NoSuchFileException) {
            project.logger.info("resources in $inputFile not found")
        } catch (@Suppress("SwallowedException") exc: java.nio.file.NoSuchFileException) {
            project.logger.info("resources in $inputFile not found (empty lib)")
        }
    }

    companion object {
        const val MERGE_RES_DIR = "mergeRes"
        const val TASK_NAME = "mergeAppleResources"
    }

}