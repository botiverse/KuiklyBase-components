/*
 * Copyright 2020 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */

package com.tencent.tmm.kmmgradle.generator.ohos

import com.android.build.gradle.tasks.MergeSourceSetFolders
import com.squareup.kotlinpoet.KModifier
import com.tencent.tmm.kmmgradle.generator.MRGenerator
import com.tencent.tmm.kmmgradle.tasks.MergeOhosResourcesTask
import com.tencent.tmm.kmmgradle.tasks.action.PackageKLibResourcesAction
import com.tencent.tmm.kmmgradle.utils.CommonUtils
import com.tencent.tmm.kmmgradle.utils.dependsOnProcessResources
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCompilation
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.io.File

class OhosMRGenerator(
    generatedDir: File,
    sourceSet: SourceSet,
    mrSettings: MRSettings,
    generators: List<Generator>,
    private val compilation: KotlinNativeCompilation
) : MRGenerator(
    generatedDir = generatedDir,
    sourceSet = sourceSet,
    mrSettings = mrSettings,
    generators = generators
) {

    override fun getMRClassModifiers(): Array<KModifier> = arrayOf(KModifier.ACTUAL)

    override fun apply(generationTask: Task, project: Project) {

        // klib resource
        setupPackageResources(generationTask)
        setupLoadResources()

        project.tasks.withType<Jar>().configureEach {
            it.dependsOn(generationTask)
        }
        project.tasks
            .matching { it.name.endsWith("SourcesJar") }
            .configureEach { it.dependsOn(generationTask) }

        project.tasks.withType<MergeSourceSetFolders>().configureEach {
            it.dependsOn(generationTask)
        }

        dependsOnProcessResources(
            project = project,
            sourceSet = sourceSet,
            task = generationTask,
            shouldExcludeGenerated = true
        )
    }

    private fun setupPackageResources(generationTask: Task) {
        val taskProvider = compilation.compileTaskProvider
        taskProvider.configure { compileTask ->
            compileTask.dependsOn(generationTask)
            // 添加res目录（资源变更时执行task）
            compileTask.inputs.dir(resourcesGenerationDir)

            val action = PackageKLibResourcesAction(resourcesGenerationDir, "tmm-resources-ohos")
            @Suppress("UNCHECKED_CAST")
            compileTask.doLast(action as Action<in Task>)
        }
    }

    private fun setupLoadResources() {
        val kotlinTarget: KotlinNativeTarget = compilation.target

        kotlinTarget.binaries
            .matching { it.compilation == compilation }
            .configureEach { binary ->
                val linkTask = binary.linkTaskProvider.get()
                val mergeResTask =
                    linkTask.project.tasks.getByName(MergeOhosResourcesTask.TASK_NAME) as MergeOhosResourcesTask
                mergeResTask.linkTask = linkTask
                mergeResTask.resourcesDir = resourcesGenerationDir
                linkTask.finalizedBy(mergeResTask)
                CommonUtils.setupTaskExecutable(mergeResTask)
            }
    }


}
