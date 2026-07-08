/*
 * Copyright 2020 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */

package com.tencent.tmm.kmmgradle.generator.apple

import com.android.build.gradle.tasks.MergeSourceSetFolders
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeSpec
import com.tencent.tmm.kmmgradle.MultiplatformResourcesPluginExtension
import com.tencent.tmm.kmmgradle.generator.MRGenerator
import com.tencent.tmm.kmmgradle.generator.apple.action.CopyResourcesFromKLibsToExecutableAction
import com.tencent.tmm.kmmgradle.generator.apple.action.PackResourcesToKLibAction
import com.tencent.tmm.kmmgradle.generator.apple.task.CopyResourcesFromKLibsToDirTask
import com.tencent.tmm.kmmgradle.generator.apple.task.CopyResourcesFromKLibsToFrameworkTask
import com.tencent.tmm.kmmgradle.generator.apple.task.PackModuleResourcesToFrameworkTask
import com.tencent.tmm.kmmgradle.tasks.CopyFrameworkResourcesToAppEntryPointTask
import com.tencent.tmm.kmmgradle.tasks.CopyFrameworkResourcesToAppTask
import com.tencent.tmm.kmmgradle.tasks.MergeAppleResourcesTask
import com.tencent.tmm.kmmgradle.utils.CommonUtils
import com.tencent.tmm.kmmgradle.utils.GradleUtils
import com.tencent.tmm.kmmgradle.utils.calculateResourcesHash
import com.tencent.tmm.kmmgradle.utils.dependsOnProcessResources
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinCommonCompile
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.cocoapods.CocoapodsExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.AbstractKotlinNativeCompilation
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable
import org.jetbrains.kotlin.gradle.tasks.FatFrameworkTask
import org.jetbrains.kotlin.gradle.tasks.FrameworkDescriptor
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink
import java.io.File
import kotlin.reflect.full.memberProperties

fun MRGenerator.MRSettings.bundleIdentifierProvider() = this.packageName.run {
    "$this.MR"
}

@Suppress("TooManyFunctions")
class AppleMRGenerator(
    generatedDir: File,
    sourceSet: SourceSet,
    settings: MRSettings,
    generators: List<Generator>,
    private val compilation: AbstractKotlinNativeCompilation,
    private val baseLocalizationRegion: String?,
) : MRGenerator(
    generatedDir = generatedDir,
    sourceSet = sourceSet,
    mrSettings = settings,
    generators = generators
) {
    private val bundleClassName = ClassName("platform.Foundation", "NSBundle")
    private val bundleIdentifierProvider = settings.bundleIdentifierProvider()

    override fun getMRClassModifiers(): Array<KModifier> = arrayOf(KModifier.ACTUAL)

    override fun processMRClass(mrClass: TypeSpec.Builder) {
        super.processMRClass(mrClass)

        mrClass.addProperty(
            PropertySpec.builder(
                BUNDLE_PROPERTY_NAME, bundleClassName, KModifier.PRIVATE
            )
                .delegate(CodeBlock.of("lazy { NSBundle.loadableBundle(\"${bundleIdentifierProvider}\") }"))
                .build()
        )

        mrClass.addProperty(
            PropertySpec.builder("contentHash", STRING, KModifier.PRIVATE)
                .initializer("%S", resourcesGenerationDir.calculateResourcesHash()).build()
        )
    }

    override fun getImports(): List<ClassName> = listOf(
        bundleClassName, ClassName("com.tencent.tmm.kmmresource.resource.utils", "loadableBundle")
    )

    override fun apply(generationTask: Task, project: Project) {

        project.tasks.withType<MergeSourceSetFolders>().configureEach {
            it.dependsOn(generationTask)
        }
        // klib resource
        // setupLoadResources()


        setupKLibResources(generationTask)
        setupFrameworkResources()
        setupTestsResources()
        setupFatFrameworkTasks()

        dependsOnProcessResources(
            project = project,
            sourceSet = sourceSet,
            task = generationTask,
            shouldExcludeGenerated = true
        )
    }

    override fun beforeMRGeneration() {
        assetsGenerationDir.mkdirs()
    }

    fun setupLoadResources() {
        val kotlinTarget: KotlinNativeTarget = compilation.target as KotlinNativeTarget

        kotlinTarget.binaries.matching { it.compilation == compilation }.configureEach { binary ->
            val linkTask = binary.linkTask
            val mergeResTask =
                linkTask.project.tasks.getByName(MergeAppleResourcesTask.TASK_NAME) as MergeAppleResourcesTask
            mergeResTask.linkTask = linkTask
            mergeResTask.resourcesDir = resourcesGenerationDir
            linkTask.finalizedBy(mergeResTask)
            CommonUtils.setupTaskExecutable(mergeResTask)
        }
    }

    private fun setupKLibResources(generationTask: Task) {
        val compileTask: KotlinNativeCompile = compilation.compileKotlinTask
        compileTask.dependsOn(generationTask)

        // tasks like compileIosMainKotlinMetadata when only one target enabled
        generationTask.project.tasks.withType<KotlinCommonCompile>()
            .matching { it.name.contains(sourceSet.name, ignoreCase = true) }
            .configureEach { it.dependsOn(generationTask) }

        compileTask.doLast(
            PackResourcesToKLibAction(
                mrSettings = mrSettings,
                baseLocalizationRegion = baseLocalizationRegion,
                bundleIdentifierProvider = bundleIdentifierProvider,
                assetsDirectoryProvider = assetsGenerationDir,
                resourcesGenerationDirProvider = resourcesGenerationDir,
            )
        )
    }

    private fun setupFrameworkResources() {
        val kotlinNativeTarget = compilation.target as KotlinNativeTarget
        val project = kotlinNativeTarget.project


        kotlinNativeTarget.binaries.matching { it is Framework && it.compilation == compilation }
            .configureEach { binary ->
                val framework = binary as Framework

                val linkTask = framework.linkTask

                val taskName = CopyResourcesFromKLibsToDirTask.TASK_NAME + "_" + linkTask.name

                val toDirTask = GradleUtils.addTaskCompact(
                    project, project.tasks, taskName, CopyResourcesFromKLibsToDirTask::class.java
                )

                toDirTask.linkTask = linkTask

                linkTask.finalizedBy(toDirTask)

                // Copy the klib-packed resources (the MR `.bundle`) into the
                // framework's own output directory. Without this the bundle only
                // lives inside the klib and never reaches the framework, so
                // neither `embedAndSignAppleFrameworkForXcode` (which embeds the
                // framework's resources) nor `copyFrameworkResourcesToApp` (which
                // copies `*.bundle` from `framework.outputFile`) have anything to
                // deliver — leaving `MR.*` unresolvable at runtime on iOS.
                val toFrameworkTaskName =
                    CopyResourcesFromKLibsToFrameworkTask.TASK_NAME + "_" + linkTask.name

                val toFrameworkTask = GradleUtils.addTaskCompact(
                    project, project.tasks, toFrameworkTaskName,
                    CopyResourcesFromKLibsToFrameworkTask::class.java
                )

                toFrameworkTask.linkTask = linkTask

                linkTask.finalizedBy(toFrameworkTask)

                // The klib-based paths above do not deliver THIS module's own MR
                // resources under recent KGP: `KotlinNativeCompile` does not run
                // ordinary `doLast` actions (so `PackResourcesToKLibAction` never
                // packs the module's klib), and the module's own compile output is
                // an unpacked klib directory that the `.klib`-extension filter in
                // the copy tasks skips. Write the owning module's MR bundle
                // straight into the framework from its generated resources.
                val packModuleTaskName =
                    PackModuleResourcesToFrameworkTask.TASK_NAME + "_" + linkTask.name

                val packModuleTask = GradleUtils.addTaskCompact(
                    project, project.tasks, packModuleTaskName,
                    PackModuleResourcesToFrameworkTask::class.java
                )

                packModuleTask.linkTask = linkTask
                packModuleTask.resourcesGenerationDir = resourcesGenerationDir
                packModuleTask.assetsDirectory = File(assetsGenerationDir.parentFile, "res")
                packModuleTask.bundleName = bundleIdentifierProvider
                packModuleTask.bundleIdentifier = bundleIdentifierProvider
                packModuleTask.baseLocalizationRegion = baseLocalizationRegion

                linkTask.finalizedBy(packModuleTask)

                if (framework.isStatic) {
                    val resourcesExtension: MultiplatformResourcesPluginExtension =
                        project.extensions.getByType()
                    if (resourcesExtension.staticFrameworkWarningEnabled) {
                        project.logger.warn(
                            """
$linkTask produces static framework, Xcode should have Build Phase with copyFrameworkResourcesToApp gradle task call. 
"""
                        )
                    }
                    createCopyFrameworkResourcesTask(linkTask)
                }
            }
    }

    private fun createCopyFrameworkResourcesTask(linkTask: KotlinNativeLink) {
        val framework = linkTask.binary as Framework
        val project = linkTask.project
        val taskName = linkTask.name.replace("link", "copyResources")

        val copyTask = project.tasks.create(taskName, CopyFrameworkResourcesToAppTask::class.java) {
            it.framework = framework
        }
        copyTask.dependsOn(linkTask)

        val xcodeTask = project.tasks.maybeCreate(
            "copyFrameworkResourcesToApp", CopyFrameworkResourcesToAppEntryPointTask::class.java
        )
        val multiplatformExtension = project.extensions.getByType<KotlinMultiplatformExtension>()
        xcodeTask.configurationMapper =
            (multiplatformExtension as? ExtensionAware)?.extensions?.findByType<CocoapodsExtension>()?.xcodeConfigurationToNativeBuildType
                ?: emptyMap()

        if (framework.target.konanTarget == xcodeTask.konanTarget && framework.buildType.getName() == xcodeTask.configuration?.lowercase()) {
            xcodeTask.dependsOn(copyTask)
        }
    }

    private fun setupTestsResources() {
        val kotlinNativeTarget = compilation.target as KotlinNativeTarget

        kotlinNativeTarget.binaries.matching {
            it is TestExecutable && it.compilation.associateWith.contains(
                compilation
            )
        }.configureEach {
            val executable = it as TestExecutable
            executable.linkTaskProvider.configure {
                it.doLast(CopyResourcesFromKLibsToExecutableAction())
            }
        }
    }

    private fun setupFatFrameworkTasks() {
        val kotlinNativeTarget = compilation.target as KotlinNativeTarget
        val project = kotlinNativeTarget.project

        @Suppress("ObjectLiteralToLambda")
        val fatAction: Action<Task> = object : Action<Task> {
            override fun execute(task: Task) {
                val fatTask: FatFrameworkTask = task as FatFrameworkTask

                // compatibility of this api was changed
                // from 1.6.10 to 1.6.20, so reflection was
                // used here.
                val fatFrameworkDir: File = FatFrameworkTask::class.memberProperties.run {
                    find { it.name == "fatFrameworkDir" } ?: find { it.name == "fatFramework" }
                }?.invoke(fatTask) as File

                val frameworkFile = when (val any: Any = fatTask.frameworks.first()) {
                    is Framework -> any.outputFile
                    is FrameworkDescriptor -> any.files.rootDir
                    else -> error("Unsupported type of $any")
                }

                executeWithFramework(fatFrameworkDir, frameworkFile)
            }

            private fun executeWithFramework(
                fatFrameworkDir: File,
                frameworkFile: File,
            ) = frameworkFile.listFiles()?.asSequence()?.filter { it.name.contains(".bundle") }
                ?.forEach { bundleFile ->
                    project.copy {
                        it.from(bundleFile)
                        it.into(File(fatFrameworkDir, bundleFile.name))
                    }
                }
        }

        project.tasks.withType(FatFrameworkTask::class).configureEach { it.doLast(fatAction) }
    }

    companion object {
        const val BUNDLE_PROPERTY_NAME = "bundle"
        const val ASSETS_DIR_NAME = "Assets.xcassets"
    }
}
