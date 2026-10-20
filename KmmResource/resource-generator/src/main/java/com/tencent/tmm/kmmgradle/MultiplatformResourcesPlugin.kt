/*
 * Copyright 2019 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */

package com.tencent.tmm.kmmgradle

import com.tencent.tmm.kmmgradle.configuration.configureAndroidTargetGenerator
import com.tencent.tmm.kmmgradle.configuration.configureAppleTargetGenerator
import com.tencent.tmm.kmmgradle.configuration.getAndroidRClassPackage
import com.tencent.tmm.kmmgradle.configuration.setupCommonTarget
import com.tencent.tmm.kmmgradle.configuration.setupOhosGenerator
import com.tencent.tmm.kmmgradle.generator.AssetsGenerator
import com.tencent.tmm.kmmgradle.generator.ColorsGenerator
import com.tencent.tmm.kmmgradle.generator.FilesGenerator
import com.tencent.tmm.kmmgradle.generator.FontsGenerator
import com.tencent.tmm.kmmgradle.generator.ImagesGenerator
import com.tencent.tmm.kmmgradle.generator.MRGenerator
import com.tencent.tmm.kmmgradle.generator.PluralsGenerator
import com.tencent.tmm.kmmgradle.generator.ResourceGeneratorFeature
import com.tencent.tmm.kmmgradle.generator.SourceInfo
import com.tencent.tmm.kmmgradle.generator.StringsGenerator
import com.tencent.tmm.kmmgradle.generator.apple.task.CopyResourcesFromKLibsToDirTask
import com.tencent.tmm.kmmgradle.generator.apple.task.CopyResourcesFromKLibsToFrameworkTask
import com.tencent.tmm.kmmgradle.tasks.MergeAppleResourcesTask
import com.tencent.tmm.kmmgradle.tasks.MergeOhosResourcesTask
import com.tencent.tmm.kmmgradle.tasks.apple.setupExecutableResources
import com.tencent.tmm.kmmgradle.tasks.apple.setupTestsResources
import com.tencent.tmm.kmmgradle.utils.GradleUtils
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinMultiplatformPluginWrapper
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.File

@Suppress("TooManyFunctions")
class MultiplatformResourcesPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val mrExtension: MultiplatformResourcesPluginExtension =
            target.extensions.create(
                "multiplatformResources",
                MultiplatformResourcesPluginExtension::class
            )
        // 去掉默认multiplatformResourcesPackage
        // mrExtension.multiplatformResourcesPackage = "${target.group}.${target.name}"

        target.plugins.withType(KotlinMultiplatformPluginWrapper::class) {
            val multiplatformExtension =
                target.extensions.getByType(KotlinMultiplatformExtension::class)

            target.afterEvaluate {
                configureGenerators(
                    project = target,
                    mrExtension = mrExtension,
                    kmpExtension = multiplatformExtension
                )
            }
        }



        initKLibResTask(target)
    }

    @Suppress("LongMethod")
    private fun configureGenerators(
        project: Project,
        mrExtension: MultiplatformResourcesPluginExtension,
        kmpExtension: KotlinMultiplatformExtension
    ) {
        // 校验multiplatformResourcesPackage
        if (mrExtension.multiplatformResourcesPackage.isNullOrEmpty()) {
            throw RuntimeException("Please configure tmm-resources multiplatformResourcesPackage")
        }
        // 校验multiplatformResourcesPrefix
        if (mrExtension.multiplatformResourcesPrefix.isNullOrEmpty()) {
            throw RuntimeException("Please configure tmm-resources multiplatformResourcesPrefix")
        }

        val mrClassPackage: String = requireNotNull(mrExtension.multiplatformResourcesPackage) {
            buildString {
                appendLine("multiplatformResources.multiplatformResourcesPackage is required!")
                append("Please configure tmm-resources plugin correctly.")
            }
        }
        val mrResPrefix: String = requireNotNull(mrExtension.multiplatformResourcesPrefix) {
            buildString {
                appendLine("multiplatformResources.multiplatformResourcesPrefix is required!")
                append("Please configure tmm-resources plugin correctly.")
            }
        }

        val commonSourceSet = kmpExtension.sourceSets.getByName(mrExtension.sourceSetName)
        val commonResources = commonSourceSet.resources


        // 配置commonMR KModifiers

        val generatedDir = File(project.buildDir, "generated/tmm-res")
        val commonGeneratedDir = mrExtension.commonGeneratedDir
            ?.takeIf { it.isNotBlank() }
            ?.let { project.file(it) }
            ?: generatedDir

        val strictLineBreaks: Boolean =
            project.findProperty("tmm.resources.strictLineBreaks").let { it as? String }
                ?.toBoolean() ?: false

        val mrSettings = MRGenerator.MRSettings(
            packageName = mrClassPackage,
            className = mrExtension.multiplatformResourcesClassName,
            visibility = mrExtension.multiplatformResourcesVisibility,
            resPrefix = mrResPrefix,
            generatedDir = generatedDir,
            commonGeneratedDir = commonGeneratedDir,
            isStrictLineBreaks = strictLineBreaks,
            iosLocalizationRegion = mrExtension.iosBaseLocalizationRegion,
            commonSourceSet = commonSourceSet,
            androidRClassPackage = project.getAndroidRClassPackage()
        )

        val sourceInfo = SourceInfo(
            generatedDir,
            commonResources,
            mrExtension.multiplatformResourcesPackage!!,
            mrSettings.androidRClassPackage
        )


        val iosLocalizationRegion = mrExtension.iosBaseLocalizationRegion

        val targets: List<KotlinTarget> = kmpExtension.targets.toList()


        kmpExtension.targets.configureEach { kotlinTarget ->
            println("Kmm kotlinTarget ${kotlinTarget.name}")
            configureKotlinTargetGenerator(
                sourceInfo,
                iosLocalizationRegion,
                strictLineBreaks,
                mrSettings,
                project,
                kotlinTarget,
                mrExtension,
                kmpExtension,
                targets
            )
        }


        // setupProjectForApple(project)
    }

    private fun configureKotlinTargetGenerator(
        sourceInfo: SourceInfo,
        iosLocalizationRegion: String,
        strictLineBreaks: Boolean,
        mrSettings: MRGenerator.MRSettings,
        project: Project,
        kotlinTarget: KotlinTarget,
        mrExtension: MultiplatformResourcesPluginExtension,
        kmpExtension: KotlinMultiplatformExtension,
        targets: List<KotlinTarget>
    ) {
        val features = listOf(
            StringsGenerator.Feature(
                info = sourceInfo,
                iosBaseLocalizationRegion = iosLocalizationRegion,
                strictLineBreaks = strictLineBreaks,
                mrSettings = mrSettings
            ),
            PluralsGenerator.Feature(
                info = sourceInfo,
                iosBaseLocalizationRegion = iosLocalizationRegion,
                strictLineBreaks = strictLineBreaks,
                mrSettings = mrSettings
            ),
            ImagesGenerator.Feature(sourceInfo, mrSettings, project.logger),
            FontsGenerator.Feature(sourceInfo, mrSettings),
            FilesGenerator.Feature(sourceInfo, mrSettings),
            ColorsGenerator.Feature(sourceInfo, mrSettings),
            AssetsGenerator.Feature(sourceInfo, mrSettings)
        )

        kotlinTarget.compilations.configureEach { compilation ->
            compilation.kotlinSourceSets.forEach { sourceSet ->
            }
        }

        when (kotlinTarget.platformType) {
            KotlinPlatformType.common -> setupCommonTarget(
                project,
                mrExtension,
                mrSettings,
                features
            )


            KotlinPlatformType.androidJvm -> configureAndroidTargetGenerator(
                kotlinTarget,
                mrSettings,
                kmpExtension.sourceSets.getByName("androidMain"),
                features
            )


            KotlinPlatformType.native -> configureNativeTargetGenerator(
                project,
                kotlinTarget as KotlinNativeTarget,
                targets,
                mrSettings,
                features
            )

            KotlinPlatformType.js,
            KotlinPlatformType.jvm,
            KotlinPlatformType.wasm -> project.project.logger.warn("js jvm wasm target not supported by Resources now")

        }
    }

    private fun initKLibResTask(project: Project) {
        // 加载klib res
        GradleUtils.addTaskCompact(
            project,
            project.tasks,
            MergeOhosResourcesTask.TASK_NAME,
            MergeOhosResourcesTask::class.java
        )
        GradleUtils.addTaskCompact(
            project,
            project.tasks,
            MergeAppleResourcesTask.TASK_NAME,
            MergeAppleResourcesTask::class.java
        )
        GradleUtils.addTaskCompact(
            project,
            project.tasks,
            CopyResourcesFromKLibsToFrameworkTask.TASK_NAME,
            CopyResourcesFromKLibsToFrameworkTask::class.java
        )
        GradleUtils.addTaskCompact(
            project,
            project.tasks,
            CopyResourcesFromKLibsToDirTask.TASK_NAME,
            CopyResourcesFromKLibsToDirTask::class.java
        )
    }

    private fun configureNativeTargetGenerator(
        project: Project,
        target: KotlinNativeTarget,
        targets: List<KotlinTarget>,
        settings: MRGenerator.MRSettings,
        features: List<ResourceGeneratorFeature<out MRGenerator.Generator>>
    ) {
        // Matched by name, not by `KonanTarget.*` objects: Kotlin removes retired targets
        // (IOS_ARM32 and WATCHOS_X86 are gone since 2.2), and a compiled reference to a removed
        // object fails project configuration with NoClassDefFoundError on newer KGP.
        when (target.konanTarget.name) {
            in APPLE_KONAN_TARGET_NAMES -> {

                setupExecutableResources(target = target)
                setupTestsResources(target = target)

                configureAppleTargetGenerator(
                    project = project,
                    target = target,
                    settings = settings,
                    features = features
                )
            }


            else -> if (target.konanTarget.name == "ohos_arm64") {
                setupOhosGenerator(
                    commonSourceSet = settings.commonSourceSet,
                    targets = targets,
                    generatedDir = settings.generatedDir,
                    mrSettings = settings,
                    features = features,
                    target = target.project
                )
            } else {
                target.project.logger.warn("$target is not supported by kmm Resources at now")
            }

        }
    }
}

private val APPLE_KONAN_TARGET_NAMES: Set<String> = setOf(
    "ios_arm32", "ios_arm64", "ios_simulator_arm64", "ios_x64",
    "macos_arm64", "macos_x64",
    "tvos_arm64", "tvos_simulator_arm64", "tvos_x64",
    "watchos_arm32", "watchos_arm64", "watchos_device_arm64", "watchos_simulator_arm64",
    "watchos_x64", "watchos_x86",
)
