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
import kotlin.test.assertTrue

/**
 * Every request-preparation path must inject the default User-Agent.
 *
 * The first version of this feature covered `execute()` but missed
 * `downloadStream()`, which prepares requests on its own path and went
 * straight to the engine after applying auth. Streaming downloads therefore
 * still carried no User-Agent, which quietly contradicted the guarantee the
 * feature was added to make.
 *
 * Auth injection marks every such path, so this pins the two together: a new
 * preparation path that applies auth and forgets the User-Agent fails here
 * instead of shipping a silent hole.
 */
class NetworkUserAgentCoverageTest {

    @Test
    fun everyPreparationPathThatAppliesAuthAlsoAppliesTheDefaultUserAgent() {
        val source = locateNetworkClientSource().readText()

        // Positive control: a count of zero would otherwise "pass" trivially if
        // the file moved or the helper were renamed.
        val authCalls = source.callSitesOf("applyCurrentAuthToken")
        val userAgentCalls = source.callSitesOf("applyDefaultUserAgent")
        assertTrue(authCalls > 0, "found no applyCurrentAuthToken call sites; did the file move?")

        assertEquals(
            authCalls,
            userAgentCalls,
            "each request-preparation path that applies auth must also apply the default " +
                "User-Agent; found $authCalls auth call sites and $userAgentCalls User-Agent ones"
        )
    }

    /** Counts invocations, excluding the declaration itself. */
    private fun String.callSitesOf(name: String): Int =
        lines().count { line ->
            val trimmed = line.trim()
            trimmed.contains("$name(") && !trimmed.startsWith("private ") && !trimmed.startsWith("fun ")
        }

    private fun locateNetworkClientSource(): File {
        val relative =
            "NetworkKMM/network/src/commonMain/kotlin/com/tencent/kmm/network/service/NetworkClient.kt"
        var directory: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relative)
            if (candidate.isFile) {
                return candidate
            }
            directory = directory.parentFile
        }
        throw AssertionError("could not locate $relative")
    }
}
