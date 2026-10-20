/*
 * Copyright 2023 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */

package com.tencent.tmm.kmmgradle.utils

import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

internal fun unzipTo(outputDirectory: File, zipFile: File) {
    ZipFile(zipFile).use { zip ->
        val outputDirectoryCanonicalPath = outputDirectory.canonicalPath
        for (entry in zip.entries()) {
            unzipEntryTo(outputDirectory, outputDirectoryCanonicalPath, zip, entry)
        }
    }
}

private fun unzipEntryTo(
    outputDirectory: File,
    outputDirectoryCanonicalPath: String,
    zip: ZipFile,
    entry: ZipEntry
) {
    val output = outputDirectory.resolve(entry.name)
    if (!output.canonicalPath.startsWith(outputDirectoryCanonicalPath)) {
        throw ZipException("Zip entry '${entry.name}' is outside of the output directory")
    }
    if (entry.isDirectory) {
        output.mkdirs()
    } else {
        output.parentFile.mkdirs()
        zip.getInputStream(entry).use { it.copyTo(output) }
    }
}

private fun InputStream.copyTo(file: File): Long =
    file.outputStream().use { copyTo(it) }

/**
 * Kotlin-version-independent replacement for
 * `KotlinLibraryLayoutImpl(klib, component = "default").extractingToTemp.resourcesDir`.
 * Kotlin 2.3.20+ removed `KotlinLibraryLayoutImpl`/`KotlinLibraryLayout` (and
 * `org.jetbrains.kotlin.konan.file.zipDirAs`), so the generator reads the klib layout itself:
 * `<klib>/default/resources`. Packed `.klib` files are extracted to a temp directory first.
 */
internal fun File.klibResourcesDir(): File {
    if (isDirectory) return File(this, "default/resources")
    val tempDir = kotlin.io.path.createTempDirectory("tmm-klib-").toFile()
    tempDir.deleteOnExit()
    unzipTo(outputDirectory = tempDir, zipFile = this)
    return File(tempDir, "default/resources")
}

/** Replacement for `org.jetbrains.kotlin.konan.file.File.zipDirAs` (removed in Kotlin 2.3.20+). */
internal fun File.zipDirectoryTo(zipFile: File) {
    val root = this
    java.util.zip.ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
        root.walkTopDown().filter { it != root }.forEach { file ->
            val name = file.relativeTo(root).invariantSeparatorsPath + if (file.isDirectory) "/" else ""
            zip.putNextEntry(ZipEntry(name))
            if (file.isFile) file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}
