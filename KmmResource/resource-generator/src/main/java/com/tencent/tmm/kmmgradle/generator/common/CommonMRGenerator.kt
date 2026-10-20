/*
 * Copyright 2020 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */

package com.tencent.tmm.kmmgradle.generator.common

import com.squareup.kotlinpoet.KModifier
import com.tencent.tmm.kmmgradle.MultiplatformResourcesPluginExtension
import com.tencent.tmm.kmmgradle.generator.MRGenerator
import com.tencent.tmm.kmmgradle.utils.CommonUtils
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import java.io.File

class CommonMRGenerator(
    val project: Project,
    generatedDir: File,
    sourceSet: SourceSet,
    mrSettings: MRSettings,
    generators: List<Generator>
) : MRGenerator(
    generatedDir = generatedDir,
    sourceSet = sourceSet,
    mrSettings = mrSettings,
    generators = generators
) {

    override fun getMRClassModifiers(): Array<KModifier> {
        val mrExtension = project.extensions.getByType(MultiplatformResourcesPluginExtension::class.java)
        return mrExtension.commonMRKModifiers
    }

    override fun apply(generationTask: Task, project: Project) {
        CommonUtils.setupTaskExecutable(generationTask)
        project.tasks
            .withType<KotlinCompilationTask<*>>()
            .matching { it.name.contains(sourceSet.name, ignoreCase = true) }
            .configureEach { it.dependsOn(generationTask) }

        project.tasks.withType<Jar>().configureEach {
            it.dependsOn(generationTask)
        }
        project.tasks
            .matching { it.name.endsWith("SourcesJar") }
            .configureEach { it.dependsOn(generationTask) }

        project.rootProject.tasks.matching {
            it.name.contains("prepareKotlinBuildScriptModel")
        }.configureEach {
            it.dependsOn(generationTask)
        }

        project.tasks
            .matching { it.name.startsWith("metadata") && it.name.endsWith("ProcessResources") }
            .configureEach {
                it.dependsOn(generationTask)
            }
    }
}
