/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tencent.kmm.network.export

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Binds [NetworkUserAgent.LIBRARY_VERSION] to the published Maven version.
 *
 * A hand-maintained version constant lags the next release silently: nothing
 * fails, and every request afterwards reports a version the build no longer is.
 * This makes the release bump fail loudly instead.
 */
class NetworkUserAgentVersionTest {

    @Test
    fun libraryVersionMatchesPublishedMavenVersion() {
        val properties = locateGradleProperties()
        // Positive control: a zero-hit search proves nothing unless the file is
        // readable and shaped as expected, so assert we actually found the key.
        val declared = properties.readLines()
            .map { it.trim() }
            .firstOrNull { it.startsWith("mavenVersion=") }
            ?.substringAfter("mavenVersion=")
            ?.trim()

        assertNotNull(declared, "mavenVersion not found in ${properties.absolutePath}")
        assertTrue(declared.isNotBlank(), "mavenVersion is blank in ${properties.absolutePath}")
        assertEquals(
            declared,
            NetworkUserAgent.LIBRARY_VERSION,
            "NetworkUserAgent.LIBRARY_VERSION drifted from mavenVersion; update it with the release bump"
        )
    }

    private fun locateGradleProperties(): File {
        var directory: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (directory != null) {
            val candidate = File(directory, "gradle.properties")
            if (candidate.isFile && candidate.readText().contains("mavenVersion=")) {
                return candidate
            }
            directory = directory.parentFile
        }
        throw AssertionError("could not locate a gradle.properties declaring mavenVersion")
    }
}
