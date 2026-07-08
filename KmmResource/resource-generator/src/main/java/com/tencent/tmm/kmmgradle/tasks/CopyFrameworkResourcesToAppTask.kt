/*
 * Copyright 2020 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */

package com.tencent.tmm.kmmgradle.tasks

import com.tencent.tmm.kmmgradle.tasks.apple.getLocalProp
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import java.io.File
import java.io.FileFilter

open class CopyFrameworkResourcesToAppTask : DefaultTask() {
    init {
        group = "tmm-resources"
    }

    @Internal
    lateinit var framework: Framework

    // Resolve the Xcode destination without forcing consumers to write it into
    // local.properties: prefer a Gradle project property (`-Ptmm.resources.*`),
    // then the raw Xcode environment variable of the same short name, and only
    // then fall back to local.properties for backwards compatibility.
    private fun Project.resolveXcodeProp(key: String, envName: String): String {
        (findProperty(key) as? String)?.takeIf { it.isNotBlank() }?.let { return it }
        System.getenv(envName)?.takeIf { it.isNotBlank() }?.let { return it }
        // Legacy fallback; getLocalProp() throws if local.properties is absent,
        // so guard it and fall through to the clear error below.
        runCatching { getLocalProp().getProperty(key) as? String }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        throw GradleException(
            "copyFrameworkResourcesToApp: missing $key — pass -P$key (or set $envName / local.properties)."
        )
    }

    @TaskAction
    fun copyResources() {
        val buildProductsDir =
            project.resolveXcodeProp("tmm.resources.BUILT_PRODUCTS_DIR", "BUILT_PRODUCTS_DIR")
        val contentsFolderPath =
            project.resolveXcodeProp("tmm.resources.CONTENTS_FOLDER_PATH", "CONTENTS_FOLDER_PATH")
        val outputDir = File("$buildProductsDir/$contentsFolderPath")

        val inputDir = framework.outputFile
        inputDir.listFiles(FileFilter { it.extension == "bundle" })?.forEach {
            project.logger.info("copy resources bundle $it to $outputDir")
            it.copyRecursively(File(outputDir, it.name), overwrite = true)
        }
    }
}
