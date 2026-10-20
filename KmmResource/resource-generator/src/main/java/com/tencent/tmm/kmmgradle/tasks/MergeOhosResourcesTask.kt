package com.tencent.tmm.kmmgradle.tasks

import com.google.gson.Gson
import com.tencent.tmm.kmmgradle.generator.ohos.data.OhosColorResource
import com.tencent.tmm.kmmgradle.generator.ohos.data.OhosPluralResource
import com.tencent.tmm.kmmgradle.generator.ohos.data.OhosStringResource
import com.tencent.tmm.kmmgradle.utils.copyAndMergeDirectory
import com.tencent.tmm.kmmgradle.utils.klibs
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink
import com.tencent.tmm.kmmgradle.utils.klibResourcesDir
import java.io.File

open class MergeOhosResourcesTask : DefaultTask() {

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
            val resDir = File(inputFile.klibResourcesDir(), "tmm-resources-ohos")
            if (resDir.exists()) {
                resDir.copyAndMergeDirectory(outputDir) { srcFile, destFile ->
                    // 合并string资源
                    if (destFile.name == "string.json") {
                        val srcStringResource =
                            Gson().fromJson(srcFile.readText(), OhosStringResource::class.java)
                        val destStringResource =
                            Gson().fromJson(destFile.readText(), OhosStringResource::class.java)
                        srcStringResource.string.addAll(destStringResource.string)

                        destFile.writeText(Gson().toJson(srcStringResource))
                    }
                    // 合并plural资源
                    if (destFile.name == "plural.json") {
                        val srcPluralsResource =
                            Gson().fromJson(srcFile.readText(), OhosPluralResource::class.java)
                        val destPluralsResource =
                            Gson().fromJson(destFile.readText(), OhosPluralResource::class.java)
                        srcPluralsResource.plural.addAll(destPluralsResource.plural)

                        destFile.writeText(Gson().toJson(srcPluralsResource))
                    }
                    // 合并color资源
                    if (destFile.name == "color.json") {
                        val srcColorResource =
                            Gson().fromJson(srcFile.readText(), OhosColorResource::class.java)
                        val destColorResource =
                            Gson().fromJson(destFile.readText(), OhosColorResource::class.java)
                        srcColorResource.color.addAll(destColorResource.color)

                        destFile.writeText(Gson().toJson(srcColorResource))
                    }
                }
            }
        } catch (@Suppress("SwallowedException") exc: kotlin.io.NoSuchFileException) {
            project.logger.info("resources in $inputFile not found")
        } catch (@Suppress("SwallowedException") exc: java.nio.file.NoSuchFileException) {
            project.logger.info("resources in $inputFile not found (empty lib)")
        }
    }

    companion object {
        const val MERGE_RES_DIR = "mergeRes"
        const val TASK_NAME = "mergeOhosResources"
    }

}