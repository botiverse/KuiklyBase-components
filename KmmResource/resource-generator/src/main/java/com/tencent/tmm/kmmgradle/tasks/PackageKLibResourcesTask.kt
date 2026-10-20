package com.tencent.tmm.kmmgradle.tasks

import com.tencent.tmm.kmmgradle.utils.unzipTo
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile
import com.tencent.tmm.kmmgradle.utils.zipDirectoryTo
import java.io.File

open class PackageKLibResourcesTask : DefaultTask() {

    @get:Internal
    internal lateinit var resourcesDir: File

    @get:Internal
    internal lateinit var compileTask: KotlinNativeCompile

    @TaskAction
    fun doAction() {
        if (resourcesDir.exists().not()) {
            return
        }
        // 解压
        val klibFile = compileTask.outputFile.get()
        val repackDir = File(klibFile.parent, klibFile.nameWithoutExtension)
        val defaultDir = File(repackDir, "default")
        val resRepackDir = File(defaultDir, "resources")
        unzipTo(zipFile = klibFile, outputDirectory = repackDir)

        // 添加资源
        if (resRepackDir.exists().not()) return
        val resDir = File(resRepackDir, "tmm-resources-ohos")
        resourcesDir.copyRecursively(
            resDir,
            overwrite = true
        )

        klibFile.delete()

        // 打包
        repackDir.zipDirectoryTo(klibFile)
        repackDir.deleteRecursively()
    }

    companion object {
        const val TASK_NAME = "packageKLibResources"
    }
}