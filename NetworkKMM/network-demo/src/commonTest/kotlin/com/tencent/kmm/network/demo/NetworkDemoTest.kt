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
package com.tencent.kmm.network.demo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class CapturingSink : NetworkDemoLogSink {
    val lines = mutableListOf<String>()
    override fun line(text: String) {
        lines.add(text)
    }
}

class NetworkDemoTest {

    // --- URL redaction: a caller-typed Base URL must not leak secrets ---

    @Test
    fun redactStripsUserInfoQueryAndFragment() {
        assertEquals(
            "https://api.example.com/v1/items",
            redactDemoUrl("https://user:pass@api.example.com/v1/items?token=secret#frag")
        )
    }

    @Test
    fun redactKeepsHostPortAndPath() {
        assertEquals("http://host:8080/p", redactDemoUrl("http://user@host:8080/p?x=1"))
    }

    @Test
    fun redactLeavesCleanUrlUntouched() {
        assertEquals("https://httpbin.org/get", redactDemoUrl("https://httpbin.org/get"))
    }

    @Test
    fun redactHandlesAuthorityOnly() {
        assertEquals("https://host", redactDemoUrl("https://user:pw@host?q=1#f"))
    }

    @Test
    fun redactSchemelessUrlIsFailSafe() {
        // Invalid/partial URL: still strip userinfo + query, never echo the raw string.
        assertEquals("host/path", redactDemoUrl("user:pass@host/path?token=secret"))
    }

    @Test
    fun redactBlankIsEmpty() {
        assertEquals("", redactDemoUrl("   "))
    }

    // --- best-effort teardown (demo grade) ---

    @Test
    fun doubleCloseIsIdempotent() {
        val demo = NetworkDemo(CapturingSink())
        demo.close()
        demo.close() // must not throw
    }

    @Test
    fun runsAfterCloseAreRejected() {
        val sink = CapturingSink()
        val demo = NetworkDemo(sink)
        demo.close()
        demo.runBuffered("https://x.test")
        demo.runStreaming("https://x.test")
        demo.runUpload("https://x.test")
        demo.runCancel("https://x.test")
        assertEquals(0, sink.lines.size) // rejected before any log / network
    }

    @Test
    fun noLogAfterClose() {
        val sink = CapturingSink()
        val demo = NetworkDemo(sink)
        demo.selectEngine("curl") // logs an engine-switch line
        val afterSwitch = sink.lines.size
        assertTrue(afterSwitch >= 1)
        demo.close()
        demo.selectEngine("ktor") // fenced by close
        assertEquals(afterSwitch, sink.lines.size)
    }
}
