package com.tencent.tmm.kmmgradle.tasks.action

import com.tencent.tmm.kmmgradle.utils.unzipTo
import org.gradle.api.Action
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile
import com.tencent.tmm.kmmgradle.utils.zipDirectoryTo
import java.io.File

class PackageKLibResourcesAction(private val resourcesDir: File, private val resToDirName: String) :
    Action<KotlinNativeCompile> {
    override fun execute(compileTask: KotlinNativeCompile) {
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
        val resDir = File(resRepackDir, resToDirName)
        resourcesDir.copyRecursively(
            resDir,
            overwrite = true
        )

        klibFile.delete()

        // 打包
        repackDir.zipDirectoryTo(klibFile)
        repackDir.deleteRecursively()
    }
}